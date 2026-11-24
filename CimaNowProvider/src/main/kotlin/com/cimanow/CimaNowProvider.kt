package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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
                null
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

    private val interstitialHost = "https://rm.freex2line.online"
    private val interstitialReferer = "https://rm.freex2line.online/2020/02/blog-post.html/"

    // EXPERIMENTAL. Runs inside the app's WebView so the interstitial's own scripts
    // (Turnstile + token fetch) execute; we wait for the /watching/?token= navigation.
    private val armScript = """
        (function(){
          try { window.open = function(){ return null; }; } catch(e) {}
          var n = 0;
          var t = setInterval(function(){
            n++;
            var b = document.getElementById('downloadbtn');
            if (b && b.getAttribute('data-link-ready') === '1') {
              b.hidden = false;
              ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(e){
                b.dispatchEvent(new MouseEvent(e, {bubbles:true, cancelable:true, view:window}));
              });
              try { b.click(); } catch(e) {}
            }
            if (n > 90) clearInterval(t);
          }, 750);
          return 'armed';
        })();
    """.trimIndent()

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Flow (verified in a desktop browser): film page has a loadon link (base64 of
        // <film>/watching/). The interstitial fetches a per-film token after Turnstile and
        // then navigates to <film>/watching/?token=...; that page only opens with a Referer
        // from the interstitial site. Token is static per film but cannot be derived.
        val filmUrl = data.trimEnd('/')
        val loadon = runCatching {
            app.get(data).document.selectFirst("a[href*=\"loadon/?link=\"]")?.attr("href")
        }.getOrNull().takeUnless { it.isNullOrEmpty() }
            ?: "$interstitialHost/loadon/?link=" + b64("$filmUrl/watching/")

        val resolver = WebViewResolver(
            interceptUrl = Regex("""cimanow\.cc/.+/watching/\?token="""),
            additionalUrls = emptyList(),
            script = armScript,
            scriptCallback = { },
            timeout = 70_000L
        )
        val req = runCatching { resolver.resolveUsingWebView(loadon).first }.getOrNull()
            ?: return false
        val watchUrl = req.url.toString()

        val hdrs = HashMap<String, String>()
        runCatching {
            req.headers.toMultimap().forEach { (k, v) -> hdrs[k] = v.joinToString(", ") }
        }
        hdrs["Referer"] = interstitialReferer
        val doc = runCatching { app.get(watchUrl, headers = hdrs).document }.getOrNull()
            ?: return false
        val html = doc.html()
        var found = false

        // Forafile entries are file-host pages (.mp4.html), not direct video.
        Regex("\"Ff(\\d+)\":\\s*\"(https?://[^\"]+)\"").findAll(html).forEach { m ->
            val q = m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
            val pageUrl = m.groupValues[2]
            var ok = runCatching { loadExtractor(pageUrl, watchUrl, subtitleCallback, callback) }.getOrDefault(false)
            if (!ok) {
                val text = runCatching { app.get(pageUrl, referer = watchUrl).text }.getOrNull().orEmpty()
                Regex("(?:file|src)\\s*[:=]\\s*[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)")
                    .find(text)?.groupValues?.get(1)?.let { direct ->
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Forafile ${q}p",
                                url = direct,
                                type = if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = q
                            }
                        )
                        ok = true
                    }
            }
            found = found || ok
        }

        // Any static embed (other servers are switched through signed core.php calls).
        doc.select("iframe[src^=\"http\"], iframe[data-src^=\"http\"]").forEach { f ->
            val src = f.attr("src").ifEmpty { f.attr("data-src") }
            found = runCatching { loadExtractor(src, watchUrl, subtitleCallback, callback) }.getOrDefault(false) || found
        }
        return found
    }
}
