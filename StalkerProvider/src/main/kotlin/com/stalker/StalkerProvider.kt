package com.stalker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Runtime session state for the Stalker/Ministra portal.
 *
 * The credential is the user's own portal URL plus the MAC address registered
 * with their subscription, entered on-device through StalkerPlugin. Nothing
 * secret lives in this repository; values are kept in the app's private
 * SharedPreferences (cleartext, not encrypted) and in memory here.
 * Portals over plain http:// expose the MAC and stream session on the wire;
 * https portals are preferred. No synthetic device identity, second-step
 * login emulation or activation bypass is performed: a portal that rejects
 * the registered MAC simply fails with its own message.
 */
object StalkerSession {
    class AuthException : Exception()

    @Volatile
    var portal: String = ""
        private set

    @Volatile
    var mac: String = ""
        private set

    @Volatile
    var endpoint: String = ""
        private set

    /** true = canonical Stalker/Ministra (Bearer token), false = simple panel. */
    @Volatile
    var fullPortal: Boolean = true
        private set

    @Volatile
    private var token: String? = null

    private val mapper = ObjectMapper()

    val hasCredentials: Boolean
        get() = portal.isNotBlank() && mac.isNotBlank()

    fun update(portal: String, mac: String) {
        this.portal = portal.trim().trimEnd('/')
        this.mac = mac.trim().uppercase()
        // Credentials changed: endpoint/mode/token/catalog must be re-resolved
        // so a previous account or portal is never served under the new one.
        endpoint = ""
        token = null
        StalkerProvider.clearCatalog()
    }

    fun restoreEndpoint(endpoint: String, fullPortal: Boolean) {
        this.endpoint = endpoint
        this.fullPortal = fullPortal
    }

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun referer(): String {
        val base = when {
            endpoint.contains("/stalker_portal/") ->
                endpoint.substringBefore("/stalker_portal/") + "/stalker_portal/"
            else -> endpoint.substringBeforeLast("/") + "/"
        }
        return base + "c/"
    }

    private fun magHeaders(withReferer: Boolean): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 234 Safari/533.3",
            "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Accept" to "*/*"
        )
        if (withReferer) h["Referer"] = referer()
        return h
    }

    private fun headers(): Map<String, String> {
        val h = magHeaders(withReferer = true)
        if (fullPortal) token?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    private fun cookies(): Map<String, String> = mapOf(
        "mac" to encode(mac),
        "stb_lang" to "en",
        "timezone" to encode("Africa/Cairo")
    )

    private fun candidates(): List<String> {
        val p = portal
        if (p.substringAfterLast('/').endsWith(".php")) return listOf(p)
        return listOf(
            "$p/portal.php",
            "$p/server/load.php",
            "$p/stalker_portal/server/load.php"
        )
    }

    /**
     * Whole-body auth-failure phrases sent by canonical Ministra (HTTP 200).
     * Anchored to the whole reply so a proxy/WAF page merely containing one
     * of the phrases is not misclassified.
     */
    private fun isAuthFailure(body: String): Boolean {
        val t = body.trim()
        return t == "Authorization failed." || t == "Access denied." ||
            t == "Unauthorized request."
    }

    private fun isStructuredAuthError(body: String): Boolean {
        val js = runCatching { mapper.readTree(body).path("js") }.getOrNull() ?: return false
        val err = js.path("error").asText("")
        val msg = js.path("msg").asText("")
        return err.contains("auth", true) || err.contains("token", true) ||
            msg.contains("unauthorized", true) || msg.contains("auth failed", true)
    }

    /**
     * Probes portal endpoint candidates and classifies the portal.
     * - handshake 2xx with a token = full portal (Bearer required).
     * - handshake 401/403, or a 2xx decisive auth rejection (whole-body
     *   canonical phrase or structured js error) = full portal too; the
     *   rejection surfaces later and the endpoint is NEVER downgraded to a
     *   token-less classification.
     * - get_genres 401/403 on the token-less probe likewise means
     *   auth-required, not absent-endpoint.
     * - only an inconclusive handshake (non-2xx absent-endpoint statuses,
     *   HTML, network failure) leads to a token-less simple-panel probe.
     */
    suspend fun ensureEndpoint(): Boolean {
        if (endpoint.isNotBlank()) return true
        for (candidate in candidates()) {
            var inconclusive = false
            val resp = try {
                app.get(
                    candidate,
                    params = mapOf(
                        "type" to "stb", "action" to "handshake",
                        "token" to "", "JsHttpRequest" to "1-xml"
                    ),
                    headers = magHeaders(withReferer = false),
                    cookies = cookies(),
                    timeout = 30L
                )
            } catch (_: Exception) {
                inconclusive = true
                null
            }
            if (resp != null) {
                if (resp.code == 401 || resp.code == 403) {
                    // Transport-level auth denial: classify auth-required and stop.
                    endpoint = candidate
                    fullPortal = true
                    return true
                }
                if (resp.code in 200..299) {
                    val body = resp.text
                    val tok = runCatching {
                        mapper.readTree(body).path("js").path("token").asText(null)
                    }.getOrNull()
                    if (!tok.isNullOrBlank()) {
                        endpoint = candidate
                        fullPortal = true
                        token = tok
                        return true
                    }
                    if (isAuthFailure(body) || isStructuredAuthError(body)) {
                        endpoint = candidate
                        fullPortal = true
                        return true
                    }
                    inconclusive = true // 2xx but not a portal handshake answer
                }
                // Other statuses (404/5xx): endpoint absent or broken here; the
                // token-less probe below will also fail and we move on.
            }
            if (!inconclusive && resp != null && resp.code !in 200..299) continue
            // Token-less simple-panel probe.
            val probe = try {
                app.get(
                    candidate,
                    params = mapOf(
                        "type" to "itv", "action" to "get_genres", "JsHttpRequest" to "1-xml"
                    ),
                    cookies = cookies(),
                    timeout = 30L
                )
            } catch (_: Exception) {
                null
            } ?: continue
            if (probe.code == 401 || probe.code == 403) {
                // Auth-required is not absent-endpoint: classify full portal.
                endpoint = candidate
                fullPortal = true
                return true
            }
            if (probe.code in 200..299) {
                val js = runCatching { mapper.readTree(probe.text).path("js") }.getOrNull()
                if (js != null && js.isArray) {
                    endpoint = candidate
                    fullPortal = false
                    return true
                }
            }
        }
        return false
    }

    /** One portal API call. Errors never carry URLs, tokens or the MAC. */
    suspend fun request(
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
        retried: Boolean = false
    ): JsonNode {
        if (!hasCredentials) throw ErrorLoadingException(
            "أضف رابط البوابة وعنوان MAC من إعدادات الإضافة " +
                "(زر الترس بجانب Stalker Portal في قائمة الإضافات)"
        )
        if (!ensureEndpoint()) throw ErrorLoadingException("تعذر الوصول للبوابة. تأكد من الرابط والشبكة")
        val resp = try {
            app.get(
                endpoint,
                params = mutableMapOf(
                    "type" to type,
                    "action" to action,
                    "JsHttpRequest" to "1-xml"
                ).also { it.putAll(extra) },
                headers = headers(),
                cookies = cookies(),
                timeout = 120L
            )
        } catch (e: Exception) {
            throw ErrorLoadingException("تعذر الاتصال بالبوابة. تأكد من الرابط والشبكة")
        }
        val text = resp.text
        if (resp.code == 401 || resp.code == 403 || isAuthFailure(text) || isStructuredAuthError(text)) {
            if (fullPortal && !retried) {
                // Token expired or rejected: re-handshake once.
                token = null
                try {
                    val resp = app.get(
                        endpoint,
                        params = mapOf(
                            "type" to "stb", "action" to "handshake",
                            "token" to "", "JsHttpRequest" to "1-xml"
                        ),
                        headers = magHeaders(withReferer = false),
                        cookies = cookies(),
                        timeout = 30L
                    )
                    val tok = runCatching {
                        mapper.readTree(resp.text).path("js").path("token").asText(null)
                    }.getOrNull()
                    if (!tok.isNullOrBlank()) {
                        token = tok
                        return request(type, action, extra, retried = true)
                    }
                } catch (_: Exception) {
                }
            }
            throw AuthException()
        }
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            throw ErrorLoadingException("رد غير صالح من البوابة")
        }
        if (root == null || root.isNull) throw ErrorLoadingException("رد فارغ من البوابة")
        return root
    }
}

class StalkerProvider : MainAPI() {
    override var mainUrl = "http://localhost" // replaced by the user's portal at runtime
    override var name = "Stalker Portal"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    private val mapper = ObjectMapper()

    override val mainPage = mainPageOf(
        SECTION_LIVE to "قنوات مباشرة",
        SECTION_MOVIE to "أفلام",
        SECTION_SERIES to "مسلسلات",
    )

    // ---------- catalog (portal-paged, cached per endpoint+MAC) ----------

    private data class LiveItem(val id: String, val name: String, val cmd: String?, val logo: String?)
    private data class VodItem(
        val id: String, val name: String, val cmd: String?, val poster: String?,
        val year: Int?, val plot: String?, val rating: String?, val isSeries: Boolean
    )

    /**
     * Lazily fetched portal pages for one listing type. Browsing pages map
     * 1:1 to portal pages so arbitrarily large catalogs are never truncated
     * on the homepage. Search and lookups walk pages lazily with tight bounds
     * (see SEARCH_MAX_PAGES/LOOKUP_MAX_PAGES) and flag [truncated] on a hit.
     */
    private inner class PagedSection<T>(
        val type: String,
        val extra: Map<String, String>,
        val mapItem: (JsonNode) -> T?
    ) {
        private val pages = mutableMapOf<Int, List<T>>()
        private var totalPages: Int = Int.MAX_VALUE
        private var key: String = ""
        private var fetchedAt: Long = 0L
        var truncated = false
            private set

        private fun currentKey() = StalkerSession.endpoint + "|" + StalkerSession.mac

        private fun expired() =
            key != currentKey() ||
                System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS ||
                fetchedAt < clearStamp

        suspend fun page(p: Int): List<T> {
            if (expired()) {
                pages.clear()
                totalPages = Int.MAX_VALUE
                truncated = false
                key = currentKey()
            }
            if (p > totalPages) return emptyList()
            pages[p]?.let { return it }
            val js = StalkerSession.request(
                type, "get_ordered_list", extra + mapOf("p" to p.toString())
            ).path("js")
            val data = js.path("data")
            val items = if (data.isArray) data.mapNotNull(mapItem) else emptyList()
            val tp = js.path("total_pages")
            totalPages = when {
                tp.isNumber || tp.isTextual && tp.asText().toIntOrNull() != null -> tp.asInt()
                items.isEmpty() -> 0
                else -> Int.MAX_VALUE // portal did not report totals; page until empty
            }
            pages[p] = items
            fetchedAt = System.currentTimeMillis()
            return items
        }

        fun hasMoreAfter(p: Int): Boolean = p < totalPages

        /** Bounded lazy scan: walks portal pages one request at a time and
         * stops as soon as [filter] has produced [cap] matches, the catalog is
         * exhausted, or [maxPages] requests were made. Never pre-fetches the
         * whole catalog. */
        suspend fun scanFilter(maxPages: Int, cap: Int, filter: (T) -> Boolean): List<T> {
            val out = mutableListOf<T>()
            var p = 1
            while (p <= totalPages && p <= maxPages && out.size < cap) {
                val items = try {
                    page(p)
                } catch (e: Exception) {
                    break
                }
                if (items.isEmpty()) break
                out.addAll(items.filter(filter))
                p++
            }
            if (p > maxPages && p <= totalPages) truncated = true
            return out
        }

        /** Bounded lookup: walks pages until [pred] matches (usually cached). */
        suspend fun find(pred: (T) -> Boolean, maxPages: Int = LOOKUP_MAX_PAGES): T? {
            var p = 1
            while (p <= totalPages && p <= maxPages) {
                val items = try {
                    page(p)
                } catch (e: Exception) {
                    break
                }
                if (items.isEmpty()) break
                items.firstOrNull(pred)?.let { return it }
                p++
            }
            if (p > maxPages && p <= totalPages) truncated = true
            return null
        }
    }

    private fun JsonNode?.s(field: String): String? {
        val n = this?.get(field) ?: return null
        return when {
            n.isArray -> n.firstOrNull()?.takeIf { it.isValueNode }?.asText()
            n.isValueNode && !n.isNull -> n.asText()
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private val liveSection = PagedSection(
        "itv",
        mapOf(
            "genre" to "*", "force_ch_link_check" to "",
            "fav" to "0", "sortby" to "number", "hd" to "0"
        )
    ) { n ->
        n.s("id")?.let { id -> LiveItem(id, n.s("name") ?: "قناة $id", n.s("cmd"), n.s("logo")) }
    }

    private val vodSection = PagedSection(
        "vod",
        mapOf("category" to "*", "sortby" to "added")
    ) { n ->
        n.s("id")?.let { id ->
            VodItem(
                id,
                n.s("name") ?: "فيديو $id",
                n.s("cmd"),
                n.s("screenshot_uri")?.let { absPoster(it) },
                n.s("year")?.take(4)?.toIntOrNull(),
                n.s("description"),
                n.s("rating_imdb"),
                n.s("is_series") == "1"
            )
        }
    }

    private val seriesSection = PagedSection(
        "series",
        mapOf("category" to "*", "sortby" to "added")
    ) { n ->
        n.s("id")?.let { id ->
            VodItem(
                id,
                n.s("name") ?: "مسلسل $id",
                n.s("cmd"),
                n.s("screenshot_uri")?.let { absPoster(it) },
                n.s("year")?.take(4)?.toIntOrNull(),
                n.s("description"),
                n.s("rating_imdb"),
                true
            )
        }
    }

    companion object {
        private const val SECTION_LIVE = "live"
        private const val SECTION_MOVIE = "movie"
        private const val SECTION_SERIES = "series"
        private const val SEARCH_CAP = 60
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
        // Search scans at most ~25 portal pages per section; deeper matches are
        // not returned (partial search, disclosed). Lookups walk further but
        // normally hit an already-cached page.
        private const val SEARCH_MAX_PAGES = 25
        private const val LOOKUP_MAX_PAGES = 100

        @Volatile
        private var clearStamp = 0L

        fun clearCatalog() {
            clearStamp = System.currentTimeMillis()
        }
    }

    private fun absPoster(uri: String): String {
        if (uri.startsWith("http")) return uri
        val host = StalkerSession.endpoint.substringBefore("://") + "://" +
            StalkerSession.endpoint.substringAfter("://").substringBefore("/")
        return if (uri.startsWith("/")) host + uri
        else StalkerSession.endpoint.substringBeforeLast("/") + "/" + uri
    }

    private fun requireCredentials() {
        if (!StalkerSession.hasCredentials) {
            throw ErrorLoadingException(
                "أضف رابط البوابة وعنوان MAC من إعدادات الإضافة " +
                    "(زر الترس بجانب Stalker Portal في قائمة الإضافات)"
            )
        }
    }

    /** Series come either from a dedicated series type or vod rows with is_series=1. */
    private suspend fun seriesPages(p: Int): Pair<List<VodItem>, Boolean> {
        val dedicated = try {
            seriesSection.page(p)
        } catch (e: Exception) {
            emptyList()
        }
        if (dedicated.isNotEmpty()) return dedicated to seriesSection.hasMoreAfter(p)
        val vod = vodSection.page(p).filter { it.isSeries }
        return vod to vodSection.hasMoreAfter(p)
    }

    private suspend fun findSeries(id: String): VodItem? =
        seriesSection.find { it.id == id } ?: vodSection.find { it.id == id && it.isSeries }

    // ---------- main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        requireCredentials()
        val p = page.coerceAtLeast(1)
        return when (request.data) {
            SECTION_LIVE -> {
                val items = liveSection.page(p)
                newHomePageResponse(
                    request.name,
                    items.map {
                        newLiveSearchResponse(it.name, itemUrl("live", it.id), TvType.Live, fix = false) {
                            posterUrl = it.logo?.let { l -> absPoster(l) }
                        }
                    },
                    liveSection.hasMoreAfter(p) && items.isNotEmpty()
                )
            }

            SECTION_MOVIE -> {
                val items = vodSection.page(p).filter { !it.isSeries }
                newHomePageResponse(
                    request.name,
                    items.map {
                        newMovieSearchResponse(it.name, itemUrl("movie", it.id), TvType.Movie, fix = false) {
                            posterUrl = it.poster
                            year = it.year
                        }
                    },
                    vodSection.hasMoreAfter(p) && items.isNotEmpty()
                )
            }

            SECTION_SERIES -> {
                val (items, hasMore) = seriesPages(p)
                newHomePageResponse(
                    request.name,
                    items.map {
                        newTvSeriesSearchResponse(it.name, itemUrl("series", it.id), TvType.TvSeries, fix = false) {
                            posterUrl = it.poster
                            year = it.year
                        }
                    },
                    hasMore && items.isNotEmpty()
                )
            }

            else -> throw ErrorLoadingException()
        }
    }

    private fun itemUrl(kind: String, id: String): String = "stalker://$kind/$id"

    /** Internal token passed as dataUrl/episode data: no credentials inside. */
    private fun dataToken(kind: String, id: String, seriesEp: String? = null): String =
        "stalker|$kind|$id|${seriesEp ?: ""}"

    // ---------- search (local filter over a bounded scan) ----------

    override suspend fun search(query: String): List<SearchResponse> {
        requireCredentials()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val movies = vodSection.scanFilter(SEARCH_MAX_PAGES, SEARCH_CAP) {
            !it.isSeries && it.name.contains(q, true)
        }.map {
            newMovieSearchResponse(it.name, itemUrl("movie", it.id), TvType.Movie, fix = false) {
                posterUrl = it.poster
                year = it.year
            }
        }
        val series = seriesSection.scanFilter(SEARCH_MAX_PAGES, SEARCH_CAP) {
            it.name.contains(q, true)
        }.ifEmpty {
            vodSection.scanFilter(SEARCH_MAX_PAGES, SEARCH_CAP) {
                it.isSeries && it.name.contains(q, true)
            }
        }.map {
            newTvSeriesSearchResponse(it.name, itemUrl("series", it.id), TvType.TvSeries, fix = false) {
                posterUrl = it.poster
                year = it.year
            }
        }
        val live = liveSection.scanFilter(SEARCH_MAX_PAGES, SEARCH_CAP) {
            it.name.contains(q, true)
        }.map {
            newLiveSearchResponse(it.name, itemUrl("live", it.id), TvType.Live, fix = false) {
                posterUrl = it.logo?.let { l -> absPoster(l) }
            }
        }
        return movies + series + live
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse {
        requireCredentials()
        // APIRepository applies fixUrl() before load(), which prefixes the
        // custom scheme with mainUrl; tolerate both the clean and the
        // prefixed form instead of relying on removePrefix.
        val parts = url.substringAfter("stalker://", "").split("/")
        val kind = parts.getOrNull(0) ?: throw ErrorLoadingException()
        val id = parts.getOrNull(1) ?: throw ErrorLoadingException()
        return when (kind) {
            "live" -> {
                val item = liveSection.find { it.id == id }
                newLiveStreamLoadResponse(
                    item?.name ?: "قناة $id",
                    url,
                    dataToken("live", id)
                ) {
                    posterUrl = item?.logo?.let { absPoster(it) }
                }
            }

            "movie" -> {
                val item = vodSection.find { it.id == id }
                newMovieLoadResponse(
                    item?.name ?: "فيلم $id",
                    url,
                    TvType.Movie,
                    dataToken("movie", id)
                ) {
                    posterUrl = item?.poster
                    plot = item?.plot
                    year = item?.year
                    score = Score.from10(item?.rating)
                }
            }

            "series" -> {
                val item = findSeries(id)
                val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                // Seasons/episodes come from vod get_ordered_list with movie_id.
                runCatching {
                    val js = StalkerSession.request(
                        "vod", "get_ordered_list",
                        mapOf("movie_id" to id, "category" to "*")
                    ).path("js")
                    if (js.isArray) {
                        js.forEach { season ->
                            val seasonName = season.s("name")
                            val seasonNo = seasonName?.filter { it.isDigit() }?.toIntOrNull()
                            val epNums = season.path("series")
                            if (epNums.isArray) {
                                epNums.forEach { epNode ->
                                    val epNum = epNode.asText().toIntOrNull() ?: return@forEach
                                    episodes.add(
                                        newEpisode(dataToken("series", id, epNum.toString()), fix = false) {
                                            this.name = "الحلقة $epNum"
                                            this.season = seasonNo
                                            this.episode = epNum
                                            this.posterUrl = season.s("screenshot_uri")?.let { absPoster(it) }
                                                ?: item?.poster
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                val sorted = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
                newTvSeriesLoadResponse(
                    item?.name ?: "مسلسل $id",
                    url,
                    TvType.TvSeries,
                    sorted
                ) {
                    posterUrl = item?.poster
                    plot = item?.plot
                    year = item?.year
                    score = Score.from10(item?.rating)
                    comingSoon = sorted.isEmpty()
                }
            }

            else -> throw ErrorLoadingException()
        }
    }

    // ---------- links ----------

    private fun resolveStreamUrl(rawCmd: String?): String? {
        if (rawCmd.isNullOrBlank()) return null
        // cmd is often "ffrt http://..." or "ffmpeg http://...": take the URL part.
        val parts = rawCmd.trim().split(" ").filter { it.isNotBlank() }
        var url = parts.firstOrNull { it.startsWith("http") } ?: return null
        // Portals may hand back localhost URLs meant for the STB itself;
        // rewrite the host with the portal's own host.
        if (url.contains("://localhost") || url.contains("://127.0.0.1")) {
            val scheme = StalkerSession.endpoint.substringBefore("://")
            val hostPort = StalkerSession.endpoint.substringAfter("://").substringBefore("/")
            url = url.replace("://localhost", "://$hostPort")
                .replace("://127.0.0.1", "://$hostPort")
            if (!url.startsWith(scheme)) url = "$scheme://" + url.substringAfter("://")
        }
        return url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        requireCredentials()
        val parts = data.split("|")
        if (parts.size < 4 || parts[0] != "stalker") throw ErrorLoadingException()
        val kind = parts[1]
        val id = parts[2]
        val seriesEp = parts[3].ifBlank { null }

        val rawCmd: String? = when (kind) {
            "live" -> liveSection.find { it.id == id }?.cmd
            "movie" -> vodSection.find { it.id == id }?.cmd
            "series" -> findSeries(id)?.cmd
            else -> null
        } ?: throw ErrorLoadingException("المحتوى غير موجود في كتالوج البوابة")

        // Ask the portal for a temporary stream link; fall back to the static cmd.
        val linkCmd: String? = runCatching {
            val type = if (kind == "live") "itv" else "vod"
            val extra = mutableMapOf(
                "cmd" to rawCmd,
                "forced_storage" to "undefined",
                "disable_ad" to "0"
            )
            if (seriesEp != null) extra["series"] = seriesEp
            StalkerSession.request(type, "create_link", extra).path("js").path("cmd").asText(null)
        }.getOrNull()

        val streamUrl = resolveStreamUrl(linkCmd ?: rawCmd)
            ?: throw ErrorLoadingException("البوابة لم ترجع رابط تشغيل")

        val isHls = kind == "live" || streamUrl.contains(".m3u8")
        val cookieHeader = "mac=${URLEncoder.encode(StalkerSession.mac, "UTF-8")}; stb_lang=en"
        callback(
            newExtractorLink(
                name,
                "Stalker $kind",
                streamUrl,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = StalkerSession.endpoint
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
                        "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 234 Safari/533.3",
                    "Cookie" to cookieHeader
                )
            }
        )
        return true
    }
}
