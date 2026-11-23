package com.yallashoot

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
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.concurrent.CancellationException
import org.jsoup.nodes.Element

class YallashootProvider : MainAPI() {
    override var mainUrl = "https://www.yallashoot.video"
    override var name = "Yallashoot"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    // {page} is replaced with the page suffix in getMainPage.
    // Page 1 has no /page/1/ suffix on this WordPress theme.
    override val mainPage = mainPageOf(
        "$mainUrl/home11/{page}" to "مباريات اليوم",
        "$mainUrl/packs/european/{page}" to "كورة أوروبية",
        "$mainUrl/packs/african/{page}" to "كورة أفريقية",
        "$mainUrl/packs/asian/{page}" to "كورة آسيوية",
        "$mainUrl/packs/latin/{page}" to "كورة لاتينية",
        "$mainUrl/packs/other/{page}" to "رياضات أخرى",
        "$mainUrl/packs/channels/{page}" to "قنوات",
        "$mainUrl/packs/highlights/{page}" to "ملخصات",
        "$mainUrl/packs/goals/{page}" to "أهداف",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("h2.title a[href*=/video/]")
            ?: selectFirst("a[href*=/video/]")
            ?: return null
        val href = fixUrl(link.attr("href"))
        val title = (link.attr("title").ifBlank { link.text() }).trim()
            .takeIf { it.isNotBlank() } ?: return null
        val img = selectFirst("img")
        val poster = (img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src"))
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrl(it) }
        return newLiveSearchResponse(title, href, TvType.Live) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paged = if (page <= 1) "" else "page/$page/"
        val document = app.get(request.data.replace("{page}", paged)).document
        val items = document.select("li.video-grid")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        ).document
        return document.select("li.video-grid")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.video-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore("|").trim().ifBlank { return null }
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

        // The match page carries the player page as embedUrl in its JSON-LD;
        // fall back to the deterministic /embed/ path.
        val matchHtml = app.get(data, timeout = 8L).text
        val embedUrl = Regex(""""embedUrl"\s*:\s*"([^"]+)"""")
            .find(matchHtml)?.groupValues?.get(1)?.let { unescapeJson(it) }
            ?: (data.trimEnd('/') + "/embed/")

        val embedHtml = app.get(embedUrl, referer = data, timeout = 8L).text

        // The player config lists every server:
        // var pj = {"data":{"sources":[{"name":"..","src":".."}, ...]}};
        val servers = Regex(""""src"\s*:\s*"(https?:[^"]+)"""")
            .findAll(embedHtml)
            .map { unescapeJson(it.groupValues[1]) }
            .distinct()
            .toList()

        val visited = mutableSetOf<String>()
        for (serverUrl in servers.take(8)) {
            try {
                found = resolveServer(serverUrl, embedUrl, 0, visited, subtitleCallback, callback) || found
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // one dead server must not kill the rest
            }
        }
        return found
    }

    /**
     * Walks a server page to a playable stream.
     * Observed chains on this site:
     *  - host page -> iframe -> page with a plain .m3u8 URL
     *  - host /ch?id=N -> /api/player.php?id=N -> JSON {"url": next embed}
     *  - embed page -> JSON-escaped signed .m3u8 (secure_hls.php?path=..&e=..&sig=..)
     */
    private suspend fun resolveServer(
        url: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 3 || visited.size >= 20) return false
        val clean = unescapeJson(url)
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) return false
        if (!visited.add(clean)) return false
        val targetHost = runCatching { java.net.URI(clean).host?.lowercase() }.getOrNull()
        if (isUnavailableHost(targetHost)) return false
        if (clean.contains(".m3u8")) {
            return emitUnencryptedHls(clean, referer, name, emptyMap(), callback)
        }

        val response = app.get(clean, referer = referer, timeout = 8L)
        if (response.code !in 200..299) return false
        val page = response.text
        if (page.contains("ContentProtection", true) || page.contains("clearKeys", true) ||
            page.contains("widevine", true) || page.contains("licenseUrl", true)) return false

        // 1. A stream URL right on the page (possibly JSON-escaped)
        val directStreams = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(unescapeJson(page))
            .map { unescapeJson(it.value) }
            .distinct()
            .toList()
        if (directStreams.isNotEmpty()) {
            var emitted = false
            for (stream in directStreams.take(8)) {
                emitted = emitUnencryptedHls(stream, clean, name, emptyMap(), callback) || emitted
            }
            if (emitted) return true
        }

        // 2. The NexaST/KoraST style API that returns the real embed URL
        val host = Regex("""https?://([^/?\s]+)""").find(clean)?.groupValues?.get(1)
        val channelId = Regex("""[?&]id=(\d+)""").find(clean)?.groupValues?.get(1)
        if (host != null && host in observedApiHosts && channelId != null) {
            val apiUrl = "https://$host/api/player.php?id=$channelId"
            val next = try {
                Regex(""""url"\s*:\s*"([^"]+)"""")
                    .find(app.get(apiUrl, referer = clean, timeout = 8L).text)
                    ?.groupValues?.get(1)?.let { unescapeJson(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (next != null && next != clean &&
                resolveServer(next, clean, depth + 1, visited, subtitleCallback, callback)
            ) {
                return true
            }
        }

        // 3. Follow the embedded iframe one level deeper
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(page)?.groupValues?.get(1)?.let { java.net.URI(clean).resolve(it).toString() }
        if (iframe != null && iframe != clean) {
            if (resolveServer(iframe, clean, depth + 1, visited, subtitleCallback, callback)) return true
        }

        // Unknown/custom-obfuscated player formats are intentionally unsupported.
        return false
    }

    // Check the actual media manifest, preserving extractor request headers.
    // Do not use DRM keys, encrypted HLS, or challenge bypasses.
    private suspend fun emitUnencryptedHls(
        streamUrl: String,
        referer: String,
        serverName: String,
        requestHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = mutableSetOf<String>()
        suspend fun visit(url: String, depth: Int): Boolean {
            if (depth > 2 || !seen.add(url)) return false
            if (isUnavailableHost(runCatching { java.net.URI(url).host?.lowercase() }.getOrNull())) return false
            val response = app.get(url, referer = referer, headers = requestHeaders, timeout = 8L)
            val text = response.text.trim().removePrefix("\uFEFF")
            if (response.code !in 200..299 || !text.startsWith("#EXTM3U")) return false
            if (Regex("""(?m)^#EXT-X-(?:SESSION-)?KEY:""").containsMatchIn(text)) return false
            val lines = text.lineSequence().map { it.trim() }.toList()
            val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }
                .mapNotNull { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } }
                .take(12)
            if (variants.isNotEmpty()) {
                var emitted = false
                for (variant in variants) {
                    try {
                        val child = java.net.URI(response.url).resolve(variant).toString()
                        emitted = visit(child, depth + 1) || emitted
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { }
                }
                return emitted
            }
            if (!text.contains("#EXTINF:")) return false
            callback(newExtractorLink(name, serverName, response.url, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = requestHeaders
            })
            return true
        }
        return visit(streamUrl, 0)
    }

    private fun isUnavailableHost(host: String?): Boolean = host != null &&
        unavailableHosts.any { host == it || host.endsWith(".$it") }

    private val observedApiHosts = setOf("nexa.st", "kora.st")
    // Sources confirmed unavailable during validation, not challenge bypass targets.
    private val unavailableHosts = setOf("dynbush.net", "edge.wedd48c.top", "hls.lshls.st")

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
    }
                      }
