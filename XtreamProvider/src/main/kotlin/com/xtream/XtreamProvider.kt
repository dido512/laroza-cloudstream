package com.xtream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Runtime session state for the Xtream account.
 * Filled by XtreamPlugin from the app's private SharedPreferences.
 * Never persisted in this repository.
 */
object XtreamSession {
    @Volatile
    var server: String = ""
        private set

    @Volatile
    var username: String = ""
        private set

    @Volatile
    var password: String = ""
        private set

    /** Bumped whenever any credential field changes; providers key caches on it. */
    @Volatile
    var generation: Long = 0L
        private set

    val hasCredentials: Boolean
        get() = server.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun update(server: String, username: String, password: String) {
        val s = normalizeServer(server)
        val u = username.trim()
        if (s != this.server || u != this.username || password != this.password) {
            this.server = s
            this.username = u
            this.password = password
            generation++
        }
    }

    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isNotBlank() && !s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    /**
     * Parses an Xtream-compatible link that carries API credentials into
     * (server, username, password), or null when the input is a plain server
     * address or a link without Xtream credentials. Supported forms:
     *  - {base}/get.php?username=U&password=P...   (the M3U playlist link)
     *  - {base}/player_api.php / panel_api.php / xmltv.php / enigma2.php
     *    with the same username/password query params
     *  - {base}/{live|movie|series}/U/P/{id}.{ext} (a stream URL)
     * Scheme, port and any base path are preserved. Arbitrary playlist URLs
     * without Xtream API credentials are never converted.
     */
    fun parseXtreamLink(raw: String): Triple<String, String, String>? {
        val s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null
        val uri = try { java.net.URI(s) } catch (e: Exception) { return null }
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val segments = (uri.rawPath ?: "").split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        fun baseUrl(dropLast: Int): String {
            val keep = segments.dropLast(dropLast)
            val path = if (keep.isEmpty()) "" else keep.joinToString("/", prefix = "/")
            return uri.scheme + "://" + uri.authority + path
        }

        fun decode(v: String): String = try {
            java.net.URLDecoder.decode(v, "UTF-8")
        } catch (e: Exception) { v }

        fun queryParam(name: String): String? {
            val q = uri.rawQuery ?: return null
            for (pair in q.split("&")) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                if (pair.substring(0, eq).equals(name, ignoreCase = true)) {
                    return decode(pair.substring(eq + 1)).trim().takeIf { it.isNotBlank() }
                }
            }
            return null
        }

        val last = segments.last().lowercase()
        if (last in setOf("get.php", "player_api.php", "panel_api.php", "xmltv.php", "enigma2.php")) {
            val u = queryParam("username") ?: return null
            val p = queryParam("password") ?: return null
            return Triple(baseUrl(1), u, p)
        }
        if (segments.size >= 4 &&
            segments[segments.size - 4].lowercase() in setOf("live", "movie", "series")
        ) {
            val u = decode(segments[segments.size - 3]).trim()
            val p = decode(segments[segments.size - 2]).trim()
            if (u.isNotBlank() && p.isNotBlank()) return Triple(baseUrl(4), u, p)
        }
        return null
    }

    val selected = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** player_api.php base including auth query params. */
    fun apiBase(): String =
        "$server/player_api.php?username=${encode(username)}&password=${encode(password)}"

    /** Standard Xtream playback URL: {server}/{kind}/{user}/{pass}/{id}.{ext} */
    fun playbackUrl(kind: String, id: String, ext: String?): String =
        "$server/$kind/${encode(username)}/${encode(password)}/$id" +
            (ext?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
}

class XtreamProvider : MainAPI() {
    override var mainUrl = "http://localhost" // replaced by the user's server at runtime
    override var name = "Xtream IPTV"
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

    // ---------- cached catalog ----------

    // Lazy, category-first browsing: only the small category list and the
    // streams of the category being opened are requested. The full
    // get_live_streams / get_vod_streams / get_series lists are never fetched.
    // Xtream has no server-side pagination or search: a category's streams
    // arrive as one array.
    private data class Cat(val id: String, val name: String)
    private data class Item(val id: String, val name: String, val image: String?, val ext: String?, val year: Int?)

    private val lock = Any()
    private var stateGen = -1L
    private val cats = HashMap<String, List<Cat>>()
    private val streams = HashMap<String, List<Item>>()
    private val known = HashMap<String, Item>()
    private val pageStart = HashMap<String, Int>()

    private fun syncGen() {
        val g = XtreamSession.generation
        if (g != stateGen) {
            cats.clear(); streams.clear(); known.clear(); pageStart.clear()
            stateGen = g
        }
    }

    private fun catAction(kind: String) = when (kind) {
        "live" -> "get_live_categories"
        "movie" -> "get_vod_categories"
        else -> "get_series_categories"
    }

    private fun streamAction(kind: String) = when (kind) {
        "live" -> "get_live_streams"
        "movie" -> "get_vod_streams"
        else -> "get_series"
    }

    private suspend fun categories(kind: String): List<Cat> {
        synchronized(lock) { syncGen(); cats[kind] }?.let { return it }
        val gen = XtreamSession.generation
        val root = fetchJson(apiAction(catAction(kind)))
        if (!root.isArray) throw ErrorLoadingException("استجابة غير متوقعة من سيرفر Xtream")
        val list = root.mapNotNull { el ->
            val id = el.s("category_id") ?: return@mapNotNull null
            Cat(id, el.s("category_name") ?: id)
        }
        synchronized(lock) { syncGen(); if (gen == stateGen) cats[kind] = list }
        return list
    }

    private suspend fun itemsOf(kind: String, cat: Cat): List<Item> {
        val key = kind + "|" + cat.id
        synchronized(lock) { syncGen(); streams[key] }?.let { return it }
        val gen = XtreamSession.generation
        val root = fetchJson(apiAction(streamAction(kind), "&category_id=" + XtreamSession.encode(cat.id)))
        if (!root.isArray) throw ErrorLoadingException("استجابة غير متوقعة من سيرفر Xtream")
        val list = root.mapNotNull { el ->
            val idField = if (kind == "series") "series_id" else "stream_id"
            val id = el.s(idField) ?: return@mapNotNull null
            Item(
                id,
                el.s("name") ?: id,
                el.s(if (kind == "series") "cover" else "stream_icon"),
                el.s("container_extension"),
                el.s("releaseDate")?.take(4)?.toIntOrNull()
            )
        }
        synchronized(lock) {
            syncGen()
            if (gen == stateGen) {
                streams[key] = list
                list.forEach { known[kind + "|" + it.id] = it }
            }
        }
        return list
    }

    private fun knownItem(kind: String, id: String): Item? =
        synchronized(lock) { syncGen(); known[kind + "|" + id] }

    // ---------- http + json helpers ----------

    private fun apiAction(action: String, extra: String = ""): String =
        "${XtreamSession.apiBase()}&action=$action$extra"

    private suspend fun fetchJson(url: String): JsonNode = kotlin.coroutines.suspendCoroutine { cont ->
        Thread { cont.resumeWith(runCatching { fetchJsonBlocking(url) }) }.start()
    }

    private fun fetchJsonBlocking(url: String): JsonNode {
        // Never let the request URL (which carries credentials) leak into an
        // exception message shown in the app's error UI.
        // Plain JDK HTTP (the same client the working connection test uses),
        // streamed straight into the JSON parser so very large catalogs are not
        // copied through an intermediate String. Messages never carry the URL.
        val root: JsonNode? = run {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = if (url.contains("&action=search&")) 15000 else 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "gzip")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw ErrorLoadingException("السيرفر رد بخطأ HTTP $code")
                }
                var stream: java.io.InputStream = conn.inputStream
                if ("gzip".equals(conn.contentEncoding, true)) stream = java.util.zip.GZIPInputStream(stream)
                try {
                    mapper.readTree(stream.buffered())
                } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                    throw ErrorLoadingException("رد غير صالح من سيرفر Xtream")
                } catch (e: java.io.IOException) {
                    throw ErrorLoadingException("انقطع الاتصال أثناء تحميل القائمة (" + e.javaClass.simpleName + ")")
                }
            } catch (e: ErrorLoadingException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw ErrorLoadingException("القائمة كبيرة جداً على ذاكرة الجهاز")
            } catch (e: Exception) {
                throw ErrorLoadingException("تعذر الاتصال بالسيرفر (" + e.javaClass.simpleName + ")")
            } finally {
                conn?.disconnect()
            }
        }
        if (root == null || root.isNull) throw ErrorLoadingException("رد فارغ من سيرفر Xtream")
        if (root.isObject && root.has("user_info") &&
            root.path("user_info").path("auth").asInt(1) == 0
        ) {
            throw ErrorLoadingException("بيانات الدخول غير صحيحة")
        }
        return root
    }

    /** Scalar text of a field, tolerating numbers-as-strings, blanks and "null". */
    private fun JsonNode?.s(field: String): String? {
        val n = this?.get(field) ?: return null
        return when {
            n.isArray -> n.firstOrNull()?.takeIf { it.isValueNode }?.asText()
            n.isValueNode && !n.isNull -> n.asText()
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun requireCredentials() {
        if (!XtreamSession.hasCredentials) {
            throw ErrorLoadingException(
                "أضف بيانات اشتراك Xtream من إعدادات الإضافة " +
                    "(زر الترس بجانب Xtream IPTV في قائمة الإضافات)"
            )
        }
        if (mainUrl != XtreamSession.server) mainUrl = XtreamSession.server
    }

    private fun itemUrl(kind: String, id: String): String =
        "${XtreamSession.server}/$kind/$id"

    /** Internal token passed as dataUrl/episode data: no credentials inside. */
    private fun dataToken(kind: String, id: String, ext: String?): String =
        "xtream|$kind|$id|${ext ?: ""}"

    private fun parseDurationMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(":")
        return when (parts.size) {
            3 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0)
            else -> raw.toIntOrNull()
        }
    }

    // ---------- main page ----------

    private fun toSearch(kind: String, it: Item): SearchResponse = when (kind) {
        "live" -> newLiveSearchResponse(it.name, itemUrl("live", it.id), TvType.Live) { posterUrl = it.image }
        "movie" -> newMovieSearchResponse(it.name, itemUrl("movie", it.id), TvType.Movie) { posterUrl = it.image }
        else -> newTvSeriesSearchResponse(it.name, itemUrl("series", it.id), TvType.TvSeries) {
            posterUrl = it.image
            year = it.year
        }
    }

    // Each home page = the next non-empty category of the section, fetched on demand.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        requireCredentials()
        val kind = request.data
        if (kind != SECTION_LIVE && kind != SECTION_MOVIE && kind != SECTION_SERIES) throw ErrorLoadingException()
        val categoryId = XtreamSession.selected[kind]
        if (categoryId.isNullOrBlank()) return newHomePageResponse(
            request.name + ": اختر القسم من ترس الإضافة", emptyList<SearchResponse>(), false)
        return try {
            val c = categories(kind).firstOrNull { it.id == categoryId }
                ?: return newHomePageResponse(request.name + ": اختر القسم مجدداً", emptyList<SearchResponse>(), false)
            val items = itemsOf(kind, c)
            val from = (page - 1).coerceAtLeast(0) * PAGE_SIZE
            newHomePageResponse(request.name + ": " + c.name,
                items.drop(from).take(PAGE_SIZE).map { toSearch(kind, it) }, items.size > from + PAGE_SIZE)
        } catch (e: Exception) {
            newHomePageResponse(request.name + ": تعذر تحميل القسم، جرّب اختبار الاتصال", emptyList<SearchResponse>(), false)
        }
    }

    // Server search is a panel extension, not part of standard Xtream V2.
    override suspend fun search(query: String): List<SearchResponse> {
        requireCredentials()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (kind in listOf(SECTION_MOVIE, SECTION_SERIES, SECTION_LIVE)) {
            val type = if (kind == SECTION_MOVIE) "movies" else kind
            val root = try { fetchJson(apiAction("search", "&type=$type&context=" + XtreamSession.encode(q))) }
                catch (e: Exception) { null }
            val remote = root?.takeIf { it.isArray }?.mapNotNull { el ->
                val id = el.s(if (kind == SECTION_SERIES) "series_id" else "stream_id") ?: return@mapNotNull null
                val title = el.s("name") ?: return@mapNotNull null
                if (!title.contains(q, true)) return@mapNotNull null
                Item(id, title, el.s(if (kind == SECTION_SERIES) "cover" else "stream_icon"),
                    el.s("container_extension"), el.s("releaseDate")?.take(4)?.toIntOrNull())
            }
            if (!remote.isNullOrEmpty()) {
                synchronized(lock) { syncGen(); remote.forEach { known[kind + "|" + it.id] = it } }
                out.addAll(remote.take(SEARCH_CAP).map { toSearch(kind, it) })
            } else {
                val local = synchronized(lock) { syncGen(); known.filterKeys { it.startsWith(kind + "|") }.values.toList() }
                out.addAll(local.filter { it.name.contains(q, true) }.take(SEARCH_CAP).map {
                    toSearch(kind, it.copy(name = it.name + " [بحث جزئي: الأقسام المفتوحة]"))
                })
            }
        }
        if (out.isEmpty()) throw ErrorLoadingException("لا نتائج. لو السيرفر لا يدعم البحث الشامل، البحث جزئي في الأقسام المفتوحة فقط")
        return out
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse {
        requireCredentials()
        // item urls are {server}/{kind}/{id}; parse the last two segments so a
        // subpath in the server URL or a changed server host does not break them.
        val segments = url.substringAfter("://").split("/").filter { it.isNotBlank() }
        val id = segments.lastOrNull() ?: throw ErrorLoadingException()
        return when (segments.getOrNull(segments.size - 2)) {
            "live" -> {
                val item = knownItem("live", id)
                newLiveStreamLoadResponse(
                    item?.name ?: "قناة $id",
                    url,
                    dataToken("live", id, null)
                ) {
                    posterUrl = item?.image
                }
            }

            "movie" -> loadMovie(url, id)
            "series" -> loadSeries(url, id)
            else -> throw ErrorLoadingException()
        }
    }

    private suspend fun loadMovie(url: String, id: String): LoadResponse {
        val cachedItem = knownItem("movie", id)
        var ext = cachedItem?.ext
        var poster = cachedItem?.image
        var plot: String? = null
        var year: Int? = null
        var score: Score? = null
        var duration: Int? = null
        var movieName = cachedItem?.name

        runCatching { fetchJson(apiAction("get_vod_info", "&vod_id=$id")) }.getOrNull()?.let { info ->
            info.get("movie_data")?.let { md ->
                ext = md.s("container_extension") ?: ext
                movieName = md.s("name") ?: movieName
            }
            info.get("info")?.let { d ->
                poster = d.s("movie_image") ?: d.s("cover_big") ?: d.s("backdrop_path") ?: poster
                plot = d.s("plot")
                year = d.s("releasedate")?.take(4)?.toIntOrNull()
                score = Score.from10(d.s("rating")) ?: score
                duration = parseDurationMinutes(d.s("duration"))
                    ?: d.s("duration_secs")?.toIntOrNull()?.div(60)
            }
        }

        return newMovieLoadResponse(
            movieName ?: "فيلم $id",
            url,
            TvType.Movie,
            dataToken("movie", id, ext)
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.duration = duration
        }
    }

    private suspend fun loadSeries(url: String, id: String): LoadResponse {
        val root = fetchJson(apiAction("get_series_info", "&series_id=$id"))
        val details = root.get("info")
        val episodesNode = root.get("episodes")

        val episodes = mutableListOf<Episode>()
        if (episodesNode != null && episodesNode.isObject) {
            val seasons = episodesNode.fields().asSequence().toList()
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            for ((seasonKey, eps) in seasons) {
                if (!eps.isArray) continue
                val seasonNo = seasonKey.toIntOrNull()
                for (ep in eps) {
                    val epId = ep.s("id") ?: continue
                    val epInfo = ep.get("info")
                    episodes.add(
                        newEpisode(dataToken("series", epId, ep.s("container_extension")), fix = false).apply {
                            this.name = ep.s("title")
                            this.season = seasonNo ?: ep.s("season")?.toIntOrNull()
                            this.episode = ep.s("episode_num")?.toIntOrNull()
                            this.posterUrl = epInfo.s("movie_image")
                            this.description = epInfo.s("plot")
                            this.score = Score.from10(epInfo.s("rating"))
                            this.runTime = epInfo.s("duration_secs")?.toIntOrNull()?.div(60)
                            addDate(epInfo.s("releasedate"))
                        }
                    )
                }
            }
        }

        val seriesName = details.s("name")
            ?: knownItem("series", id)?.name
            ?: "مسلسل $id"

        return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
            this.posterUrl = details.s("cover")
                ?: knownItem("series", id)?.image
            this.plot = details.s("plot")
            this.year = details.s("releaseDate")?.take(4)?.toIntOrNull()
            this.score = Score.from10(details.s("rating"))
            this.tags = details.s("genre")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            this.comingSoon = episodes.isEmpty()
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        requireCredentials()
        val parts = data.split("|")
        if (parts.size < 4 || parts[0] != "xtream") throw ErrorLoadingException()
        val kind = parts[1]
        val id = parts[2]
        val ext = parts[3].ifBlank {
            when (kind) {
                "live" -> "m3u8"
                else -> "mp4"
            }
        }
        val path = when (kind) {
            "live", "movie", "series" -> kind
            else -> throw ErrorLoadingException()
        }
        val url = XtreamSession.playbackUrl(path, id, ext)
        val type =
            if (ext.equals("m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, "Xtream $kind", url, type) {
                this.referer = XtreamSession.server
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    companion object {
        private const val SECTION_LIVE = "live"
        private const val SECTION_MOVIE = "movie"
        private const val SECTION_SERIES = "series"
        private const val PAGE_SIZE = 40
        private const val SEARCH_CAP = 60
        private const val SEARCH_MAX_CATEGORIES = 25
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
