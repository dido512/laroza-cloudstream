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

    // Keyed by the session generation so any credential change, including a
    // password change for the same username, never serves stale catalog data.
    private data class Cache<T>(val generation: Long, val at: Long, val items: List<T>)

    private data class LiveItem(val id: String, val name: String, val icon: String?)
    private data class VodItem(
        val id: String, val name: String, val icon: String?,
        val ext: String?, val rating: String?
    )
    private data class SeriesItem(
        val id: String, val name: String, val cover: String?,
        val plot: String?, val rating: String?, val year: Int?
    )

    private val cacheLock = Any()
    private var liveCache: Cache<LiveItem>? = null
    private var vodCache: Cache<VodItem>? = null
    private var seriesCache: Cache<SeriesItem>? = null

    private suspend fun <T> cached(
        read: () -> Cache<T>?,
        write: (Cache<T>) -> Unit,
        action: String,
        parse: (JsonNode) -> List<T>
    ): List<T> {
        val now = System.currentTimeMillis()
        val gen = XtreamSession.generation
        synchronized(cacheLock) { read() }?.let {
            if (it.generation == gen && now - it.at < CACHE_TTL_MS) return it.items
        }
        val root = fetchJson(apiAction(action))
        if (!root.isArray) throw ErrorLoadingException("استجابة غير متوقعة من سيرفر Xtream")
        val items = parse(root)
        synchronized(cacheLock) {
            // A credential change during the fetch must not store the old account's data.
            if (gen == XtreamSession.generation) write(Cache(gen, now, items))
        }
        return items
    }

    private suspend fun liveItems(): List<LiveItem> =
        cached({ liveCache }, { liveCache = it }, "get_live_streams") { root ->
            root.mapNotNull { el ->
                val id = el.s("stream_id") ?: return@mapNotNull null
                LiveItem(id, el.s("name") ?: "قناة $id", el.s("stream_icon"))
            }
        }

    private suspend fun vodItems(): List<VodItem> =
        cached({ vodCache }, { vodCache = it }, "get_vod_streams") { root ->
            root.mapNotNull { el ->
                val id = el.s("stream_id") ?: return@mapNotNull null
                VodItem(
                    id,
                    el.s("name") ?: "فيلم $id",
                    el.s("stream_icon"),
                    el.s("container_extension"),
                    el.s("rating")
                )
            }
        }

    private suspend fun seriesItems(): List<SeriesItem> =
        cached({ seriesCache }, { seriesCache = it }, "get_series") { root ->
            root.mapNotNull { el ->
                val id = el.s("series_id") ?: return@mapNotNull null
                SeriesItem(
                    id,
                    el.s("name") ?: "مسلسل $id",
                    el.s("cover"),
                    el.s("plot"),
                    el.s("rating"),
                    el.s("releaseDate")?.take(4)?.toIntOrNull()
                )
            }
        }

    // ---------- http + json helpers ----------

    private fun apiAction(action: String, extra: String = ""): String =
        "${XtreamSession.apiBase()}&action=$action$extra"

    private suspend fun fetchJson(url: String): JsonNode {
        // Never let the request URL (which carries credentials) leak into an
        // exception message shown in the app's error UI.
        val text = try {
            app.get(url, timeout = 120L).text
        } catch (e: Exception) {
            throw ErrorLoadingException("تعذر الاتصال بالسيرفر. تأكد من الرابط والشبكة")
        }
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            throw ErrorLoadingException("رد غير صالح من سيرفر Xtream")
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        requireCredentials()
        val from = (page - 1).coerceAtLeast(0) * PAGE_SIZE

        fun <T> slice(items: List<T>) = items.drop(from).take(PAGE_SIZE)
        fun hasNext(size: Int) = size > from + PAGE_SIZE

        return when (request.data) {
            SECTION_LIVE -> {
                val items = liveItems()
                newHomePageResponse(
                    request.name,
                    slice(items).map {
                        newLiveSearchResponse(it.name, itemUrl("live", it.id), TvType.Live) {
                            posterUrl = it.icon
                        }
                    },
                    hasNext(items.size)
                )
            }

            SECTION_MOVIE -> {
                val items = vodItems()
                newHomePageResponse(
                    request.name,
                    slice(items).map {
                        newMovieSearchResponse(it.name, itemUrl("movie", it.id), TvType.Movie) {
                            posterUrl = it.icon
                        }
                    },
                    hasNext(items.size)
                )
            }

            SECTION_SERIES -> {
                val items = seriesItems()
                newHomePageResponse(
                    request.name,
                    slice(items).map {
                        newTvSeriesSearchResponse(it.name, itemUrl("series", it.id), TvType.TvSeries) {
                            posterUrl = it.cover
                            year = it.year
                        }
                    },
                    hasNext(items.size)
                )
            }

            else -> throw ErrorLoadingException()
        }
    }

    // ---------- search (local filter; Xtream has no search endpoint) ----------

    override suspend fun search(query: String): List<SearchResponse> {
        requireCredentials()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val movies = vodItems()
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(SEARCH_CAP)
            .map {
                newMovieSearchResponse(it.name, itemUrl("movie", it.id), TvType.Movie) {
                    posterUrl = it.icon
                }
            }
        val series = seriesItems()
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(SEARCH_CAP)
            .map {
                newTvSeriesSearchResponse(it.name, itemUrl("series", it.id), TvType.TvSeries) {
                    posterUrl = it.cover
                    year = it.year
                }
            }
        val live = liveItems()
            .filter { it.name.contains(q, ignoreCase = true) }
            .take(SEARCH_CAP)
            .map {
                newLiveSearchResponse(it.name, itemUrl("live", it.id), TvType.Live) {
                    posterUrl = it.icon
                }
            }
        return movies + series + live
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
                val item = liveItems().firstOrNull { it.id == id }
                newLiveStreamLoadResponse(
                    item?.name ?: "قناة $id",
                    url,
                    dataToken("live", id, null)
                ) {
                    posterUrl = item?.icon
                }
            }

            "movie" -> loadMovie(url, id)
            "series" -> loadSeries(url, id)
            else -> throw ErrorLoadingException()
        }
    }

    private suspend fun loadMovie(url: String, id: String): LoadResponse {
        val cachedItem = vodItems().firstOrNull { it.id == id }
        var ext = cachedItem?.ext
        var poster = cachedItem?.icon
        var plot: String? = null
        var year: Int? = null
        var score: Score? = Score.from10(cachedItem?.rating)
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
            ?: seriesItems().firstOrNull { it.id == id }?.name
            ?: "مسلسل $id"

        return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
            this.posterUrl = details.s("cover")
                ?: seriesItems().firstOrNull { it.id == id }?.cover
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
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
