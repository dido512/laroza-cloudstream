package com.yala5live

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import org.jsoup.nodes.Element

/** Today's actual match links and observed public embed/HLS player chains. */
class Yala5LiveProvider : MainAPI() {
    override var mainUrl = "https://www.yala5live.com"
    override var name = "Yala5Live"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)
    override val mainPage = mainPageOf(
        "$mainUrl/matches-today/" to "مباريات اليوم"
    )

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private val blogHost = "worldwaro1.blogspot.com"

    private val pageHosts = setOf(
        blogHost, "xi.yallamo.cfd", "pl.matchlivehd.com", "2cdn.syria-player.info",
        "go.yalla1live.xyz", "new.yallashootstream.com"
    )
    // Numbered redirector subdomains rotate (1., 2., ...), so allow the whole domain.
    private val pageSuffixes = listOf(".draximo.site", ".korasimo.xyz")
    private fun allowedPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty()
        uri.scheme in setOf("https", "http") &&
            (host in pageHosts || pageSuffixes.any { host.endsWith(it) }) &&
            uri.userInfo == null && (uri.port == -1 || uri.port in setOf(80, 443))
    }.getOrDefault(false)

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private fun isBlogPage(url: String): Boolean = hostOf(url) == blogHost && allowedPage(url)

    /** The site sometimes publishes a short link instead of a direct page. */
    private fun isShortLink(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "t.co" && uri.userInfo == null
    }.getOrDefault(false)

    private fun Element.card(): SearchResponse? {
        // The plain href now points at the site's own SEO article page; the
        // actual player link lives in per-device data attributes that the
        // site's script copies into href inside a browser. Read them directly.
        val link = selectFirst("a.alba-device-match-link") ?: return null
        val mobile = link.absUrl("data-mobile-url").ifBlank { link.attr("data-mobile-url") }.trim()
        val desktop = link.absUrl("data-desktop-url").ifBlank { link.attr("data-desktop-url") }.trim()
        val target = listOf(mobile, desktop).firstOrNull { isBlogPage(it) }
            ?: listOf(mobile, desktop).firstOrNull { isShortLink(it) }
            ?: return null
        val teams = select(".team-name").map { it.text().trim() }
        val title = teams.joinToString(" × ").ifBlank { link.attr("title").trim() }
        if (title.isBlank()) return null
        val poster = selectFirst(".team-logo img[src]")?.absUrl("src").orEmpty()
        val detail = listOf(
            selectFirst(".date")?.text(), selectFirst(".match-time")?.text(),
            selectFirst(".mi-channel")?.text(), selectFirst(".mi-commentator")?.text(),
            selectFirst(".mi-league")?.text()
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" | ")
        // Different matches can share one channel URL. Keep each card's identity
        // and metadata in a fragment, never sent to the player server.
        val item = target.substringBefore('#') + "#yala5=" +
            listOf(title, poster, detail).joinToString("&") { encode(it) }
        return newLiveSearchResponse(title, item, TvType.Live) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), false)
        val document = app.get(request.data, timeout = 12L).document
        val cards = document.select(".match-container").mapNotNull { it.card() }
            .distinctBy { it.url }
        return newHomePageResponse(HomePageList(request.name, cards), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search the actual schedule, not news articles from WordPress search.
        val document = app.get("$mainUrl/matches-today/", timeout = 12L).document
        return document.select(".match-container").mapNotNull { it.card() }
            .filter { it.name.contains(query.trim(), ignoreCase = true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val player = url.substringBefore('#')
        if (!allowedPage(player) && !isShortLink(player)) return null
        val metadata = url.substringAfter("#yala5=", "").split('&')
        if (metadata.size != 3) return null
        val values = runCatching { metadata.map { decode(it) } }.getOrNull() ?: return null
        if (values[0].isBlank()) return null
        // Homepage previews also call load(). Do not fetch any player here.
        return newLiveStreamLoadResponse(values[0], url, player) {
            posterUrl = values[1].takeIf { it.isNotBlank() }
            plot = values[2] + "\nالبث من روابط المباراة على الموقع. قد يعرض السيرفر برامج أو مباريات أخرى قبل موعد اللقاء، والتوفر تابع للمصدر."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = mutableSetOf<String>()
        val emitted = mutableSetOf<String>()
        suspend fun resolve(url: String, referer: String, depth: Int): Boolean {
            if (depth > 7 || visited.size >= 48 || !(allowedPage(url) || isShortLink(url)) || !visited.add(url)) return false
            try {
                val response = app.get(url, referer = referer, timeout = 10L)
                if (response.code !in 200..299 || !allowedPage(response.url)) return false
                val host = URI(response.url).host
                val raw = response.text
                // Syria Player's normal document.write wrapper is hex-escaped
                // markup. Decode the literal only; never execute page scripts.
                val unwrapped = if (host == "2cdn.syria-player.info") {
                    Regex("""document\.write\("([\s\S]*?)"\)""").find(raw)
                        ?.groupValues?.get(1)?.let { literal ->
                            Regex("""\\x([0-9a-fA-F]{2})""").replace(literal) {
                                it.groupValues[1].toInt(16).toChar().toString()
                            }
                        } ?: raw
                } else raw
                val document = org.jsoup.Jsoup.parse(unwrapped, response.url)
                val streams = mutableListOf<String>()
                val generated = mutableListOf<List<String>>()
                val nextPages = mutableListOf<String>()
                for (template in document.select("template#albaSrc")) {
                    val lines = org.jsoup.parser.Parser.unescapeEntities(template.html(), false)
                        .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                    if (lines.size > 16) continue
                    for (line in lines) {
                        val candidate = line.substringBefore(' ').trim()
                        val uri = runCatching { URI(response.url).resolve(candidate) }.getOrNull() ?: continue
                        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null) continue
                        if (uri.path.endsWith(".m3u8", ignoreCase = true)) streams.add(uri.toString())
                        else if (allowedPage(uri.toString())) nextPages.add(uri.toString())
                    }
                }
                if (host == "xi.yallamo.cfd") {
                    // Observed AlbaPlayer mobile/desktop config repeats the
                    // same plain HLS in a video src and Clappr source field.
                    for (script in document.select(".aplr-player-content script:not([src])")) {
                        Regex("""source\s*:\s*["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""")
                            .findAll(script.data()).forEach { streams.add(it.groupValues[1]) }
                    }
                }
                // Plain stream assignments (Syria Player, Korasimo/Clappr pages).
                Regex("""(?:const|let|var)\s+streamUrl\s*=\s*"(https?://[^"]+\.m3u8[^"]*)"""")
                    .findAll(unwrapped).forEach { streams.add(it.groupValues[1]) }
                // AlbaPlayer v14 keeps the stream base64 in a data attribute,
                // sometimes with comma-separated fallbacks; first entry is primary.
                for (container in document.select(".aplr-player-container[data-src]")) {
                    val rawSrc = container.attr("data-src").trim()
                    val decodedSrc = if (container.attr("data-encoded").equals("true", ignoreCase = true)) {
                        runCatching { decodeBase64(rawSrc) }.getOrNull()
                    } else rawSrc
                    // Every comma-separated entry is a separate channel/server.
                    val entries = decodedSrc?.split(',')?.map { it.trim() }.orEmpty()
                    val direct = entries.filter { it.contains(".m3u8") }
                    if (direct.isNotEmpty()) direct.forEach { streams.add(it) }
                    else if (container.attr("data-direct").equals("false", ignoreCase = true)) {
                        // AlbaPlayer v14 non-direct mode: data-src is a channel id and the
                        // player opens https://<random subdomain>.<allowed domain>/hls/<id>/master.m3u8.
                        // Any 12-32 letter subdomain resolves; try the page's own domain list.
                        val domains = Regex("[A-Za-z0-9.-]+").findAll(
                            org.jsoup.parser.Parser.unescapeEntities(container.attr("data-domains"), false)
                        ).map { it.value }.filter { it.contains('.') }.toList()
                        for (channel in entries.filter { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }) {
                            generated.add(domains.map { "https://${albaSubdomain()}.$it/hls/$channel/master.m3u8" })
                        }
                    }
                }
                if (host == "go.yalla1live.xyz") {
                    // player.html serves the HLS playlist at a disguised path:
                    // /live/<ch>/cover.webp, with ch from ?ch= (default "main").
                    val ch = URI(response.url).rawQuery.orEmpty().split('&')
                        .firstOrNull { it.startsWith("ch=") }?.removePrefix("ch=")
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "main"
                    streams.add("https://go.yalla1live.xyz/live/$ch/cover.webp")
                }
                if (host == "pl.matchlivehd.com") {
                    for (script in document.select(".aplr-player-content script:not([src])")) {
                        Regex("""AlbaPlayerControl\(['"]([A-Za-z0-9+/=]+)['"],\s*['"]hls['"]\)""")
                            .find(script.data())?.groupValues?.get(1)?.let { encoded ->
                                runCatching { decodeBase64(encoded) }
                                    .getOrNull()?.let { streams.add(it) }
                            }
                    }
                }
                var found = false
                for (stream in streams.distinct()) {
                    val uri = runCatching { URI(stream) }.getOrNull() ?: continue
                    if (uri.scheme !in setOf("https", "http") || uri.userInfo != null) continue
                    try {
                        // Skip only streams proven encrypted or not HLS. If the
                        // check cannot reach the stream (network error, 403 from
                        // this client), still offer the server so the player can try.
                        val verdict = if (!emitted.add(stream)) false else try {
                            unencryptedHls(stream, response.url)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { true }
                        if (verdict) {
                            val pageUri = URI(response.url)
                            val serverTag = pageUri.rawQuery.orEmpty().split('&')
                                .firstOrNull { it.startsWith("serv=") || it.startsWith("channel=") }
                                ?.take(32).orEmpty()
                            val streamTail = uri.path.split('/').filter { it.isNotBlank() }.takeLast(2).firstOrNull().orEmpty().take(16)
                            val label = listOf(host, pageUri.path.trimEnd('/').substringAfterLast('/').take(24), serverTag, streamTail)
                                .filter { it.isNotBlank() }.joinToString(" / ")
                            callback(newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                                this.referer = response.url
                            })
                            found = true
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                for (group in generated) {
                    // One working domain per channel is enough; they are mirrors.
                    for (stream in group.take(6)) {
                        val ok = try { unencryptedHls(stream, response.url, strict = true) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        if (ok && emitted.add(stream)) {
                            val pageUri = URI(response.url)
                            val label = listOf(host, pageUri.path.trimEnd('/').substringAfterLast('/').take(24),
                                pageUri.rawQuery.orEmpty().take(16), stream.substringAfter("/hls/").substringBefore('/'))
                                .filter { it.isNotBlank() }.joinToString(" / ")
                            callback(newExtractorLink(name, label, stream, ExtractorLinkType.M3U8) {
                                this.referer = response.url
                            })
                            found = true
                            break
                        }
                    }
                }
                // Keep collecting every supported server, even after one plays.
                nextPages.addAll(document.select("iframe#playerframe[src], .posts-body iframe[src], .post-body iframe[src], .aplr-player-content iframe[src]")
                    .map { it.absUrl("src") }.filter { allowedPage(it) })
                nextPages.addAll(document.select(".aplr-menu a[href]")
                    .map { it.absUrl("href") }.filter { allowedPage(it) })
                // Prefer the site's own default player first, then the public
                // normal player path over the secondary Alba fallback, but never
                // invent channel URLs or modify server IDs.
                val ordered = nextPages.distinct().sortedBy {
                    when (runCatching { URI(it).host }.getOrNull()) {
                        "go.yalla1live.xyz" -> 0
                        "2cdn.syria-player.info" -> 1
                        "xi.yallamo.cfd" -> 2
                        "pl.matchlivehd.com" -> 3
                        else -> 4
                    }
                }
                if (ordered.size > 16) return false
                for (next in ordered) found = resolve(next, response.url, depth + 1) || found
                return found
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return false }
        }
        return try {
            resolve(data, mainUrl, 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun albaSubdomain(): String {
        val length = 12 + kotlin.random.Random.nextInt(21)
        val builder = StringBuilder(length)
        while (builder.length < length) {
            val next = ('a'..'z').random()
            if (builder.isNotEmpty() && builder[builder.length - 1] == next) continue
            builder.append(next)
        }
        return builder.toString()
    }

    // Pure decoder keeps this provider compatible with Android API 21 and JVM.
    private fun decodeBase64(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var count = 0
        for (char in value) {
            if (char == '=') break
            val digit = alphabet.indexOf(char)
            require(digit >= 0)
            bits = (bits shl 6) or digit
            count += 6
            if (count >= 8) {
                count -= 8
                out.write((bits shr count) and 255)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** Keep the master URL so separate audio tracks and adaptive quality survive. */
    private suspend fun unencryptedHls(url: String, referer: String, strict: Boolean = false): Boolean {
        val checked = mutableSetOf<String>()
        suspend fun inspect(target: String, depth: Int): Boolean {
            if (depth > 3 || checked.size >= 32) return false
            if (!checked.add(target)) return true
            val response = app.get(target, referer = referer, timeout = 10L)
            val text = response.text.trim().removePrefix("\uFEFF")
            // Non-2xx means we could not inspect it: inconclusive, not encrypted.
            if (response.code !in 200..299) return !strict
            if (!text.startsWith("#EXTM3U")) return false
            val lines = text.lineSequence().map { it.trim() }.toList()
            val keys = lines.filter { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") }
            if (keys.any { !Regex("""(?:^|,)METHOD=NONE(?:,|$)""").containsMatchIn(it.substringAfter(':')) }) return false
            val children = mutableListOf<String>()
            for (i in lines.indices) {
                if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return false
                    children.add(next)
                }
                if (lines[i].startsWith("#EXT-X-MEDIA:")) {
                    Regex("""URI="([^"]+)"""").find(lines[i])?.groupValues?.get(1)?.let { children.add(it) }
                }
            }
            if (children.isEmpty()) return lines.any { it.startsWith("#EXTINF:") }
            if (children.size > 24) return false
            for (child in children.distinct()) {
                val next = URI(response.url).resolve(child)
                if (next.scheme !in setOf("http", "https") || !inspect(next.toString(), depth + 1)) return false
            }
            return true
        }
        return inspect(url, 0)
    }
                          }
