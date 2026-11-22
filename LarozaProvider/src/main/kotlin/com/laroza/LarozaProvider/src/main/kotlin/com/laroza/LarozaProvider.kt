package com.laroza

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class LarozaProvider : MainAPI() {
    override var mainUrl = "https://laroza1.cfd"
    override var name = "Laroza"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    override val mainPage = mainPageOf(
        "$mainUrl/newvideos1.php?page=" to "أخر الاضافات",
        "$mainUrl/category.php?cat=all_movies_13&page=" to "افلام اجنبية",
        "$mainUrl/category.php?cat=arabic-movies35&page=" to "افلام عربي",
        "$mainUrl/category.php?cat=arabic-series46&page=" to "مسلسلات عربية",
        "$mainUrl/category.php?cat=turkish-3isk-seriess48&page=" to "مسلسلات تركية",
        "$mainUrl/category.php?cat=english-series10&page=" to "مسلسلات اجنبية",
        "$mainUrl/category.php?cat=11indian-series&page=" to "مسلسلات هندية",
        "$mainUrl/category.php?cat=masrh-5&page=" to "مسرحيات",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href*=video.php], a[href*=view-serie1]") ?: return null
        val href = fixUrl(link.attr("href"))
        val title = link.attr("title").ifBlank {
            link.selectFirst("img")?.attr("alt") ?: ""
        }.trim()
        if (title.isBlank()) return null
        val img = link.selectFirst("img")
        val poster = (img?.attr("data-echo")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src"))?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        return if (href.contains("view-serie1")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val items = document.select("ul#pm-grid li").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search.php?keywords=" + java.net.URLEncoder.encode(query, "UTF-8")).document
        return document.select("ul#pm-grid li").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().ifBlank { return null }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")

        if (url.contains("view-serie1")) {
            val episodes = document.select("section.ls-season-panel").flatMap { panel ->
                val season = panel.attr("data-season").toIntOrNull()
                panel.select("article.ls-card").mapNotNull { card ->
                    val a = card.selectFirst("a[href*=video.php]") ?: return@mapNotNull null
                    val epUrl = fixUrl(a.attr("href"))
                    newEpisode(epUrl) {
                        this.name = a.attr("title").ifBlank { a.text() }.trim()
                        this.season = season
                        this.episode = card.attr("data-episode").toIntOrNull()
                        this.posterUrl = card.selectFirst("img")?.attr("src")
                            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
                    }
                }
            }.distinctBy { it.data }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1) Find the embed page for this video
        val pageDoc = app.get(data).document
        val vid = Regex("[?&]vid=([a-zA-Z0-9]+)").find(data)?.groupValues?.get(1)
        val embedUrl = pageDoc.selectFirst("a[href*=embed.php]")?.attr("href")?.let { fixUrl(it) }
            ?: vid?.let { "$mainUrl/embed.php?vid=$it" }
            ?: return false

        // 2) The embed page iframes the real player host
        val embedHtml = app.get(embedUrl, referer = data).text
        val playerUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(embedHtml)?.groupValues?.get(1)?.let { fixUrl(it) }
            ?: return false

        // 3) The player page hides the stream URL in packed javascript
        val playerHtml = app.get(playerUrl, referer = embedUrl).text
        val scripts = Regex("""eval\(function\(p,a,c,k,e,d\).*?</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(playerHtml)
            .map { it.value.removeSuffix("</script>").trim() }
            .toList()

        val candidates = mutableListOf<String>()
        for (script in scripts) {
            JsUnpacker(script).unpack()?.let { candidates.add(it) }
        }
        if (candidates.isEmpty()) candidates.add(playerHtml)

        val streams = candidates.flatMap { text ->
            Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").findAll(text).map { it.value }.toList()
        }.distinct()

        for (streamUrl in streams) {
            M3u8Helper.generateM3u8(
                this.name,
                streamUrl,
                referer = playerUrl
            ).forEach(callback)
            found = true
        }
        return found
    }
}
