package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class CimaNowProvider : MainAPI() {
    override var mainUrl = "https://cimanow.cc"
    override var name = "CimaNow"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "$mainUrl/home/" to "الرئيسية"
    )

    private fun typeOf(url: String, title: String): TvType = when {
        url.contains("/selary/") -> TvType.TvSeries
        url.contains("الحلقة") -> TvType.TvSeries
        title.contains("فيلم|حفلة|مسرحية".toRegex()) -> TvType.Movie
        else -> TvType.TvSeries
    }

    private fun searchResponse(url: String, title: String, poster: String?, year: Int?): SearchResponse {
        return if (typeOf(url, title) == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    /** Small slider cards: a > ul > li[aria-label=ribbon/year] + li[aria-label=title] + img[data-src]. */
    private fun smallCard(a: Element): SearchResponse? {
        val url = a.attr("href")
        if (url.isEmpty() || url.contains("/category/")) return null
        val titleEl = a.selectFirst("li[aria-label=\"title\"]") ?: return null
        // Title text with the trailing <em>genres</em> excluded.
        val title = titleEl.ownText().trim()
        if (title.isEmpty()) return null
        val img = a.selectFirst("img")
        val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") }
        val year = a.selectFirst("li[aria-label=\"year\"]")?.text()?.trim()?.toIntOrNull()
        return searchResponse(url, title, poster, year)
    }

    /** Big featured/search cards: article > picture > a > img[src, alt=title]. */
    private fun bigCard(a: Element): SearchResponse? {
        val url = a.attr("href")
        if (url.isEmpty() || url.contains("/category/") || url.contains("/actor/") || url.contains("/release-year/")) return null
        val img = a.selectFirst("img") ?: return null
        val title = img.attr("alt").trim()
        if (title.isEmpty()) return null
        val article = a.parents().firstOrNull { it.tagName() == "article" }
        val year = article?.selectFirst("a[href*=\"release-year\"]")?.text()?.trim()?.toIntOrNull()
        val poster = img.attr("src").ifEmpty { img.attr("data-src") }
        return searchResponse(url, title, poster, year)
    }

    private fun cardsFrom(root: Element): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        root.select("a:has(li[aria-label=\"title\"])").forEach { a ->
            smallCard(a)?.let { out.putIfAbsent(it.url, it) }
        }
        root.select("article picture a").forEach { a ->
            bigCard(a)?.let { out.putIfAbsent(it.url, it) }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val lists = doc.select("section").mapNotNull { section ->
            val span = section.selectFirst("> span") ?: return@mapNotNull null
            val name = span.ownText().trim().ifEmpty {
                span.text().substringBefore("شاهد الكل").trim()
            }
            if (name.isEmpty() || name.contains("وجهتك") || name.contains("إقرا الخبر")) return@mapNotNull null
            val items = cardsFrom(section)
            if (items.isEmpty()) null else HomePageList(name, items)
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()
        fun collect(doc: org.jsoup.nodes.Document) {
            doc.select("section article").forEach { article ->
                article.selectFirst("picture a")?.let { bigCard(it) }?.let { result.putIfAbsent(it.url, it) }
            }
        }
        val first = app.get("$mainUrl/page/1/?s=$query").document
        collect(first)
        val max = first.select("ul[aria-label=\"pagination\"] li").not(".active")
            .lastOrNull()?.text()?.toIntOrNull() ?: 1
        (2..minOf(max, 5)).forEach { p ->
            runCatching { collect(app.get("$mainUrl/page/$p/?s=$query").document) }
        }
        return result.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("title")?.text()?.split(" | ")?.firstOrNull()?.trim()
            ?: doc.selectFirst("meta[property=\"og:title\"]")?.attr("content").orEmpty()
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val year = doc.selectFirst("a[href*=\"release-year\"]")?.text()?.trim()?.toIntOrNull()
        val plot = doc.selectFirst("ul#details li:contains(عن المحتوى) p")?.text()?.trim()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content")
        val tags = doc.select("article ul li a[href*=\"/category/\"]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val trailer = doc.selectFirst("ul.tabcontent.trailer iframe")?.attr("data-src")
        val recommendations = doc.select("ul#related li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val rTitle = img?.attr("alt")?.trim()?.ifEmpty { a.attr("aria-label") } ?: a.attr("aria-label")
            if (rTitle.isEmpty()) return@mapNotNull null
            searchResponse(
                a.attr("href"), rTitle,
                img?.attr("src")?.ifEmpty { img.attr("data-src") },
                null, Qualities.Unknown.value
            )
        }

        // Series page (/selary/...): episodes listed in ul#eps. Episode page: ul#episodes.
        val episodes = (doc.select("ul#eps li a") + doc.select("ul#episodes li a")).mapNotNull { a ->
            val href = a.attr("href").ifEmpty { return@mapNotNull null }
            val num = a.selectFirst("em")?.text()?.trim()?.toIntOrNull()
                ?: a.ownText().trim().toIntOrNull()
                ?: a.text().trim().toIntOrNull()
            val epName = a.selectFirst("img[alt!=logo]")?.attr("alt")
                ?: a.attr("aria-label").ifEmpty { a.text() }
            newEpisode(href) {
                this.name = epName
                this.episode = num
                this.posterUrl = a.selectFirst("img[alt!=logo]")?.attr("src")
            }
        }.distinctBy { it.data }.sortedBy { it.episode }
        val isMovie = episodes.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                if (!trailer.isNullOrEmpty()) addTrailer(trailer)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                if (!trailer.isNullOrEmpty()) addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The site's watch flow: content page -> rm.freex2line.online/loadon interstitial
        // (10s countdown + Cloudflare Turnstile) -> /watching/?token=<server-issued token>.
        // The token is gated by Turnstile and anti-automation checks that explicitly detect
        // CloudStream by name, so it cannot be minted by plain HTTP requests. We still try
        // the watch page directly: if the site serves it (weakened checks, token reuse),
        // parse what is statically present. Forafile stream URLs ship inside the page as
        // an ffQualities JSON object.
        val watchUrl = data.trimEnd('/') + "/watching/"
        val doc = runCatching { app.get(watchUrl).document }.getOrNull() ?: return false
        var found = false

        Regex("\"Ff(\\d+)\":\\s*\"(https?://[^\"]+)\"").findAll(doc.html()).forEach { m ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Forafile",
                    url = m.groupValues[2],
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = watchUrl
                    this.quality = m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                }
            )
            found = true
        }

        // Watch servers (<li data-id data-index>) resolve through signed core.php calls
        // (anti-bot JS) and cannot be replayed; emit any static embed iframe if present.
        doc.select("li[aria-label=\"embed\"] iframe[src^=\"http\"]").forEach { iframe ->
            found = loadExtractor(iframe.attr("src"), watchUrl, subtitleCallback, callback) || found
        }
        return found
    }
}
