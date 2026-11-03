package com.crunchymirror

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import kotlin.reflect.KClass
import okhttp3.FormBody
import java.net.URLEncoder
import com.crunchymirror.BuildConfig

class CrunchyrollProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private const val BASE_URL = "https://api.crunchyroll.com"
        private const val CMS_BASE_URL = "https://cms-api.crunchyroll.com"

        val jsonParser = object : ResponseParser {
            val mapper: ObjectMapper = jacksonObjectMapper().configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
            ).configure(
                JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
            )

            override fun <T : Any> parse(text: String, kClass: KClass<T>): T = mapper.readValue(text, kClass.java)
            override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? = try { mapper.readValue(text, kClass.java) } catch (_: Exception) { null }
            override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
        }

        inline fun <reified T : Any> parseJson(text: String): T = jsonParser.parse(text, T::class)
        inline fun <reified T : Any> tryParseJson(text: String): T? = try { jsonParser.parseSafe(text, T::class) } catch (_: Exception) { null }
    }

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en-US"
    override var mainUrl = BASE_URL
    override var name = "Crunchyroll"
    override val hasMainPage = true

    private val httpClient = Requests(responseParser = jsonParser).apply {
        defaultHeaders = mapOf("User-Agent" to "Crunchyroll/3.60.0 Android/14", "Accept" to "application/json", "Content-Type" to "application/json")
    }

    private var authToken: String? = null
    private var authTokenExpiry: Long = 0

    private suspend fun getAuthToken(): String {
        if (authToken != null && System.currentTimeMillis() < authTokenExpiry) return authToken!!
        val formBody = FormBody.Builder().add("grant_type", "client_credentials").add("client_id", BuildConfig.CRUNCHYROLL_CLIENT_ID).add("client_secret", BuildConfig.CRUNCHYROLL_CLIENT_SECRET).build()
        val client = httpClient.baseClient.newBuilder().build()
        val request = okhttp3.Request.Builder().url("$BASE_URL/auth/v1/token").post(formBody).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty auth response")
        val auth = tryParseJson<AuthTokenResponse>(body) ?: throw Exception("Parse auth failed: $body")
        authToken = auth.access_token
        authTokenExpiry = System.currentTimeMillis() + (auth.expires_in * 1000) - 60000
        return authToken!!
    }

    private fun buildUrl(base: String, query: Map<String, String>) = "$base?${query.map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.joinToString("&")}"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val token = getAuthToken()
        val url = buildUrl("$CMS_BASE_URL/content/v2/discover/popular", mapOf("limit" to "20", "offset" to ((page - 1) * 20).toString(), "locale" to lang, "content_types" to "series,movies"))
        val resp = httpClient.get(url, headers = mapOf("Authorization" to "Bearer $token")).parsed<DiscoverResponse>()

        val items = resp.data.orEmpty().map { c ->
            val pUrl = c.images?.poster?.firstOrNull()?.url
            val cId = ContentId(c.id, c.type).toJson()
            newAnimeSearchResponse(c.name, cId) { posterUrl = pUrl }
        }
        return newHomePageResponse(listOf(HomePageList("Popular", items, false)), hasNext = resp.meta?.has_next == true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val token = getAuthToken()
        val url = buildUrl("$CMS_BASE_URL/content/v2/search", mapOf("q" to query, "limit" to "20", "locale" to lang, "content_types" to "series,movies"))
        val resp = httpClient.get(url, headers = mapOf("Authorization" to "Bearer $token")).parsed<SearchResponseData>()
        return resp.data.orEmpty().map { c ->
            val pUrl = c.images?.poster?.firstOrNull()?.url
            val cId = ContentId(c.id, c.type).toJson()
            newAnimeSearchResponse(c.name, cId) { posterUrl = pUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val token = getAuthToken()
        val cId = tryParseJson<ContentId>(url) ?: return null
        val dUrl = buildUrl("$CMS_BASE_URL/content/v2/${cId.id}", mapOf("locale" to lang))
        val resp = httpClient.get(dUrl, headers = mapOf("Authorization" to "Bearer $token")).parsed<ContentDetailResponse>()
        val c = resp.data
        val title = c.name
        val pUrl = c.images?.poster?.firstOrNull()?.url
        val plot = c.description
        val genre = c.genres?.map { it.name }?.toList()
        val rating = c.rating?.let { Score.from10(it) }
        val isMovie = c.type == "movie"

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = pUrl
                backgroundPosterUrl = pUrl
                this.plot = plot
                tags = genre
                score = rating
            }
        }

        val dubEps = mutableListOf<Episode>()
        val subEps = mutableListOf<Episode>()
        c.seasons?.forEach { s ->
            s.episodes?.forEach { ep ->
                val epNum = ep.number ?: 1
                val epUrl = ContentId("${cId.id}/episodes/${ep.id}", "episode").toJson()
                val dub = ep.audio_locales?.any { it in setOf("es-419", "es-ES", "en-US", "pt-BR") } == true
                val sub = ep.subtitle_locales?.any { it in setOf("es-419", "es-ES", "en-US", "pt-BR") } == true
                val epObj = newEpisode(epUrl) { name = ep.title ?: "Episode $epNum"; episode = epNum; season = s.number }
                if (dub) dubEps.add(epObj)
                if (sub || !dub) subEps.add(epObj)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = pUrl
            backgroundPosterUrl = pUrl
            this.plot = plot
            tags = genre
            score = rating
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val token = getAuthToken()
        val cId = tryParseJson<ContentId>(data) ?: return false
        val parts = cId.id.split("/")
        if (parts.size < 3) return false
        val epId = parts[2]

        val sUrl = buildUrl("$BASE_URL/play/v1/streams/$epId", mapOf("policy" to "premium", "drm" to "widevine", "format" to "hls+vtt"))
        val sResp = httpClient.get(sUrl, headers = mapOf("Authorization" to "Bearer $token")).parsed<StreamResponse>()
        val stream = sResp.stream ?: return false
        val hls = stream.hls_url ?: return false

        callback(newExtractorLink("Crunchyroll", "Crunchyroll", hls, type = ExtractorLinkType.M3U8) { referer = "https://www.crunchyroll.com"; headers = mapOf("Origin" to "https://www.crunchyroll.com") })

        stream.subtitles?.forEach { sub ->
            val sUrl = sub.url ?: return@forEach
            val sLang = sub.locale ?: "Unknown"
            subtitleCallback(SubtitleFile(sLang, sUrl))
        }
        return true
    }

    // Data classes
    data class ContentId(val id: String, val type: String = "content") { fun toJson(): String = jsonParser.writeValueAsString(this) }
    data class AuthTokenResponse(val access_token: String, val token_type: String, val expires_in: Long, val scope: String)
    data class DiscoverResponse(val data: List<ContentItem>? = null, val meta: DiscoverMeta? = null)
    data class DiscoverMeta(val has_next: Boolean = false, val total: Int = 0)
    data class SearchResponseData(val data: List<ContentItem>? = null)

    data class ContentItem(
        val id: String, val name: String, val type: String,
        val description: String? = null, val genres: List<Genre>? = null,
        val images: ContentImages? = null, val rating: Double? = null, val seasons: List<CrSeason>? = null
    )
    data class Genre(val id: String, val name: String)
    data class ContentImages(val poster: List<ImageItem>? = null, val banner: List<ImageItem>? = null)
    data class ImageItem(val url: String, val width: Int? = null, val height: Int? = null)

    data class CrSeason(val id: String, val number: Int? = null, val title: String? = null, val episodes: List<EpisodeItem>? = null)
    data class EpisodeItem(val id: String, val number: Int? = null, val title: String? = null, val audio_locales: List<String>? = null, val subtitle_locales: List<String>? = null)

    data class ContentDetailResponse(val data: ContentItem)
    data class StreamResponse(val stream: StreamData? = null)
    data class StreamData(val hls_url: String? = null, val quality: String? = null, val drm: String? = null, val drm_license_url: String? = null, val subtitles: List<SubtitleData>? = null)
    data class SubtitleData(val locale: String? = null, val url: String? = null, val format: String? = null)
}