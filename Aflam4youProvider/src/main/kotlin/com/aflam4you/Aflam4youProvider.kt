package com.aflam4you

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.util.concurrent.CancellationException

class Aflam4youProvider : MainAPI() {
    override var mainUrl = "https://channel.aflam4you.net"
    override var name = "Aflam4you"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    // {page} is replaced with the page number in getMainPage
    override val mainPage = mainPageOf(
        "$mainUrl/browse-watch-koora_live-tv-live-videos-{page}-date.html" to "قنوات الرياضة",
        "$mainUrl/browse-watch-shahid-tv-live-videos-{page}-date.html" to "قنوات عامة وأفلام",
        "$mainUrl/browse-watch-news-for-today-tv-live-videos-{page}-date.html" to "قنوات الأخبار",
        "$mainUrl/browse-watch-documment-tv-live-videos-{page}-date.html" to "قنوات وثائقية",
        "$mainUrl/browse-watch-junnuir-tv-live-videos-{page}-date.html" to "قنوات الأطفال",
        "$mainUrl/browse-watch-islamic-tv-live-videos-{page}-date.html" to "قنوات إسلامية",
        "$mainUrl/new-videos.html?page={page}" to "الجديد",
        "$mainUrl/top-videos.html?page={page}" to "الأكثر مشاهدة",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href*=-tv_]") ?: return null
        val href = fixUrl(link.attr("href"))
        val img = link.selectFirst("img") ?: selectFirst("a[href*=-tv_] img")
        val title = selectFirst("a.ellipsis")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = (img?.attr("data-echo")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src"))?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        return newLiveSearchResponse(title, href, TvType.Live) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("{page}", page.toString())
        val document = app.get(url).document
        val items = document.select("ul.pm-ul-browse-videos li")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search.php?keywords=" + java.net.URLEncoder.encode(query, "UTF-8")
        ).document
        return document.select("ul.pm-ul-browse-videos li")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore("—").trim().ifBlank { return null }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        return newLiveStreamLoadResponse(title, url, url) {
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

        // Channel page -> preview iframe -> play page link (carries the current key)
        val playerafPath = run {
            val channelHtml = app.get(data).text
            val previewUrl = Regex("""<iframe[^>]+src=["']([^"']*zzremb\d*\.php[^"']*)["']""")
                .find(channelHtml)?.groupValues?.get(1)?.let { fixUrl(it) }
            val fromPreview = previewUrl?.let { pv ->
                runCatching {
                    Regex("""playeraf\.php\?[^'"\s]+""")
                        .find(app.get(pv, referer = data).text)?.value
                }.getOrNull()
            }
            fromPreview
        } ?: return false

        val firstServerUrl = fixUrl("/$playerafPath")
        val firstHtml = app.get(firstServerUrl, referer = data).text

        // The play page lists every server for the channel
        val servers = Jsoup.parse(firstHtml)
            .select("ul#servers li a[href*=playeraf.php]")
            .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() }?.let { h -> fixUrl(h) } }
            .ifEmpty { listOf(firstServerUrl) }
            .distinct()

        servers.forEachIndexed { index, serverUrl ->
            try {
                val html = if (index == 0 && serverUrl == firstServerUrl) firstHtml
                    else app.get(serverUrl, referer = data).text
                val embedC = Regex("""var embed_c = '([^']+)'""")
                    .find(html)?.groupValues?.get(1) ?: return@forEachIndexed

                // embed_c is base64 applied twice; the result is either an
                // external player iframe or a direct stream URL
                val decoded = runCatching { base64Decode(base64Decode(embedC)) }
                    .getOrNull()?.trim() ?: return@forEachIndexed
                val target = if (decoded.contains("<iframe", ignoreCase = true)) {
                    Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                } else decoded
                val streamOrPlayer = target?.replace("&amp;", "&")?.takeIf { it.startsWith("http") }
                    ?: return@forEachIndexed
                val serverName = "$name Server ${index + 1}"

                when {
                    streamOrPlayer.contains(".m3u8") -> {
                        found = emitUnencryptedHls(streamOrPlayer, serverUrl, serverName, callback) || found
                    }
                    streamOrPlayer.contains(".mpd") || streamOrPlayer.contains("/manifest") -> {
                        // DASH sources on this site may require DRM. Do not use keys or licenses.
                    }
                    else -> {
                        // Built-in extractors handle supported hosts without custom bypass logic.
                        val candidates = mutableListOf<ExtractorLink>()
                        loadExtractor(streamOrPlayer, serverUrl, subtitleCallback) { candidates.add(it) }
                        for (link in candidates) {
                            if (link.isM3u8) {
                                found = emitUnencryptedHls(link.url, link.referer, link.name, callback) || found
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // one dead server must not kill the rest
            }
        }
        return found
    }

    // Emit only clear HLS media playlists. No DRM, decryption keys, or challenge bypass.
    private suspend fun emitUnencryptedHls(
        streamUrl: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(streamUrl, referer = referer)
        val manifest = response.text.trim()
        if (!manifest.startsWith("#EXTM3U") || manifest.contains("#EXT-X-SESSION-KEY")) return false
        if (Regex("""#EXT-X-KEY:.*METHOD=(?!NONE)[A-Z0-9-]+""").containsMatchIn(manifest)) return false
        val links = M3u8Helper.generateM3u8(name, streamUrl, referer = referer, name = serverName)
        var emitted = false
        for (link in links) {
            val media = app.get(link.url, referer = referer).text.trim()
            if (!media.startsWith("#EXTM3U") || media.contains("#EXT-X-SESSION-KEY")) continue
            if (Regex("""#EXT-X-KEY:.*METHOD=(?!NONE)[A-Z0-9-]+""").containsMatchIn(media)) continue
            callback(link)
            emitted = true
        }
        return emitted
    }
}
