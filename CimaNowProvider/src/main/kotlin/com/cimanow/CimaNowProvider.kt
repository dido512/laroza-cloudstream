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

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    private fun unb64(s: String): String? = runCatching {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
    }.getOrNull()

    private fun hostOf(u: String): String =
        runCatching { java.net.URI(u).host ?: u.take(30) }.getOrDefault(u.take(30))

    /** Redirect anonymizers (href.li/?<url>) carry the target inside the URL: unwrap without a request. */
    private fun unwrapRedirector(url: String): String {
        val m = Regex("""^https?://(?:www\.)?href\.li/\?(https?://\S+)$""").find(url.trim())
        return m?.groupValues?.get(1) ?: url
    }

    // v7. Only observes and handles the href.li hop. Auto-clicking was removed: the site only
    // accepts trusted (real touch) clicks on the interstitial button, which an injected script
    // cannot produce. The resolver re-injects this on every intercepted request and passes the
    // return value to scriptCallback, so each call reports the current page state.
    private val armScript = """
        (function(){
          function state(){
            try {
              var b = document.getElementById('downloadbtn');
              var vis = false, ready = 'x';
              if (b) {
                try { vis = getComputedStyle(b).display !== 'none'; } catch(e) {}
                ready = b.getAttribute('data-link-ready') || 'x';
              }
              return 'host=' + location.host +
                ';path=' + location.pathname.slice(0, 40) +
                ';btn=' + (b ? (vis ? 'v' : 'h') : 'n') +
                ';ready=' + ready +
                ';len=' + Math.round(document.documentElement.innerHTML.length / 20000);
            } catch(e) { return 'state-err'; }
          }
          try {
            if (/(^|\.)href\.li$/.test(location.host) && location.search.length > 8) {
              var t = decodeURIComponent(location.search.substring(1));
              if (/^https?:\/\//.test(t) && !window.__cnHop) { window.__cnHop = 1; location.replace(t); return 'hop ' + t.slice(0, 80); }
            }
          } catch(e) {}
          return state();
        })();
    """.trimIndent()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = StringBuilder()
        fun d(s: String) {
            if (diag.isNotEmpty()) diag.append(" | ")
            diag.append(s)
            android.util.Log.w("CimaNowDiag", "CimaNow v7 diag: " + s.take(350))
        }
        android.util.Log.w("CimaNowDiag", "CimaNow v7 diag: loadLinks start")
        var found = false
        try {
            val filmUrl = data.trimEnd('/')
            val loadon = runCatching {
                app.get(data).document.selectFirst("a[href*=\"loadon/?link=\"]")?.attr("href")
            }.getOrNull().takeUnless { it.isNullOrEmpty() }
            d(if (loadon != null) "S1 loadon link found" else "S1 no loadon link on film page (built it)")
            val rawStart = loadon ?: ("$interstitialHost/loadon/?link=" + b64("$filmUrl/watching/"))

            // The direct watching URL is embedded base64 in the loadon link; keep it as fallback.
            val watchGuess = Regex("""[?&]link=([^&]+)""").find(rawStart)?.groupValues?.get(1)
                ?.let { unb64(it) }?.takeIf { it.contains("/watching") }
            d("S1b watchGuess " + (if (watchGuess != null) "ok host=" + hostOf(watchGuess) else "none"))

            // v7: no plain-request walk. A plain HTTP client is redirected to a decoy blog; only a real WebView gets the interstitial.
            val start = rawStart
            d("S1c webview start host=" + hostOf(start))

            // State strings reported by the injected script on each reinjection.
            val states = java.util.concurrent.CopyOnWriteArrayList<String>()
            val resolver = WebViewResolver(
                interceptUrl = Regex("""cimanow\.[a-z.]+/.+/watching/"""),
                script = armScript,
                scriptCallback = { raw ->
                    val s = raw.removeSurrounding("\"").take(300)
                    if (s.isNotBlank() && s != "null" && states.lastOrNull() != s) {
                        states.add(s)
                        android.util.Log.w("CimaNowDiag", "CimaNow v7 state: " + s)
                    }
                },
                timeout = 70_000L
            )
            val res = runCatching {
                resolver.resolveUsingWebView(url = start, referer = null, headers = emptyMap(), method = "GET", requestCallBack = { false })
            }
            var watchUrl: String? = null
            var watchHeaders: Map<String, String> = emptyMap()
            val pair = res.getOrNull()
            if (pair == null) {
                d("S2 WebView threw " + (res.exceptionOrNull()?.javaClass?.simpleName ?: "?"))
            } else {
                d("S2 states=" + states.size + " last=[" + (states.lastOrNull() ?: "none") + "]")
                val req = pair.first
                if (req == null) {
                    d("S3 no /watching/ request within 70s")
                } else {
                    watchUrl = req.url.toString()
                    d("S3 got watching url (token hidden)")
                    val hdrs = HashMap<String, String>()
                    runCatching { req.headers.toMultimap().forEach { (k, v) -> hdrs[k] = v.joinToString(", ") } }
                    watchHeaders = hdrs
                }
            }
            if (watchUrl == null && watchGuess != null) {
                d("S3b falling back to direct watching page")
                watchUrl = watchGuess
            }
            if (watchUrl != null) {
                val hdrs = HashMap(watchHeaders)
                hdrs["Referer"] = interstitialReferer
                val resp = runCatching { app.get(watchUrl, headers = hdrs) }
                val r = resp.getOrNull()
                if (r == null) {
                    d("S4 fetch threw " + (resp.exceptionOrNull()?.javaClass?.simpleName ?: "?"))
                } else {
                    val doc = r.document
                    val html = doc.html()
                    val title = doc.title().take(40)
                    d("S4 http=" + r.code + " len=" + html.length + " title=" + title)
                    val ffs = Regex("\"Ff(\\d+)\":\\s*\"(https?://[^\"]+)\"").findAll(html).toList()
                    val iframes = doc.select("iframe[src^=\"http\"], iframe[data-src^=\"http\"]")
                    d("S5 Ff=" + ffs.size + " iframes=" + iframes.size + " dataid=" + doc.select("[data-id]").size)
                    var extOk = 0
                    ffs.forEach { m ->
                        val q = m.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                        val pageUrl = m.groupValues[2]
                        var ok = runCatching { loadExtractor(pageUrl, watchUrl, subtitleCallback, callback) }.getOrDefault(false)
                        if (!ok) {
                            val tr = runCatching { app.get(pageUrl, referer = watchUrl) }.getOrNull()
                            val text = tr?.text.orEmpty()
                            if (extOk == 0 && !found) d("S6 embed http=" + (tr?.code ?: -1) + " len=" + text.length)
                            Regex("(?:file|src)\\s*[:=]\\s*[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)")
                                .find(text)?.groupValues?.get(1)?.let { direct ->
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "CimaNow ${q}p",
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
                        if (ok) extOk++
                        found = found || ok
                    }
                    iframes.forEach { f ->
                        val src = f.attr("src").ifEmpty { f.attr("data-src") }
                        found = runCatching { loadExtractor(src, watchUrl, subtitleCallback, callback) }.getOrDefault(false) || found
                    }
                    d("S7 extractor links ok=" + extOk)
                }
            }
        } catch (e: Throwable) {
            d("ERR " + e.javaClass.simpleName)
        }
        if (!found) {
            // Supported diagnostic surface: app logcat (Settings > Show logcat, tag CimaNowDiag).
            val msg = "CimaNow v7 diag: " + diag.toString().take(400)
            android.util.Log.w("CimaNowDiag", msg)
        }
        return found
    }
}
