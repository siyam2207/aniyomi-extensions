package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val apiBaseUrl = "https://www.eporner.com/api/v2/video/"
    private val json: Json by injectLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }

    // ====== API Data Classes ======
    @Serializable
    data class ApiResponse(
        val videos: List<ApiVideo>?,
        val total: Int,
        val page: Int,
        val perpage: Int
    )

    @Serializable
    data class ApiVideo(
        val id: String,
        val title: String,
        val description: String? = null,
        val keywords: String? = null,
        val views: Int,
        val rate: String,
        val url: String,          // slug like "/vid123/title"
        val added: String,        // date string
        val length_sec: Int,
        val thumb: ThumbDetails? = null
    )

    @Serializable
    data class ThumbDetails(
        val src: String? = null
    )

    @Serializable
    data class ApiVideoDetails(
        val video: ApiVideoDetail
    )

    @Serializable
    data class ApiVideoDetail(
        val id: String,
        val title: String,
        val description: String? = null,
        val keywords: String? = null,
        val views: Int,
        val rate: String,
        val url: String,
        val added: String,
        val length_sec: Int,
        val thumb: ThumbDetails? = null,
        val files: List<VideoFile>? = null
    )

    @Serializable
    data class VideoFile(
        val quality: String,   // e.g. "1080p", "720p"
        val url: String,       // direct video URL
        val mime: String? = null
    )

    // ====== Popular ======
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "mostviewed")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val apiResponse = parseApiResponse(response)
        val animeList = apiResponse.videos?.mapNotNull { it.toSAnime() } ?: emptyList()
        val hasNextPage = (apiResponse.videos?.size ?: 0) == apiResponse.perpage
        return AnimesPage.create(animeList, hasNextPage)
    }

    // ====== Latest ======
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ====== Search ======
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("order", "latest") // default; could be made filterable
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
        // Add filter params if implemented
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====== Anime Details ======
    override fun animeDetailsRequest(anime: SAnime): Request {
        // Extract video ID from stored URL (format: https://www.eporner.com/vid123/title)
        val videoId = anime.url.substringAfter("/vid").substringBefore("/")
        val url = "$apiBaseUrl/id/?id=vid$videoId"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val apiVideo = parseVideoDetailFromResponse(response)
        return SAnime.create().apply {
            url = baseUrl + apiVideo.url
            title = apiVideo.title
            thumbnail_url = apiVideo.thumb?.src
            description = apiVideo.description
            artist = apiVideo.keywords
            status = SAnime.COMPLETED
            // Store video ID in a custom field? Not needed; we can extract from url later
        }
    }

    // ====== Episode List ======
    override fun episodeListParse(response: Response): List<SEpisode> {
        val apiVideo = parseVideoDetailFromResponse(response) // reuse details
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1
            // Store the video ID as the episode URL (to be used in videoListRequest)
            url = apiVideo.id
            date_upload = parseDateToUnix(apiVideo.added)
        }
        return listOf(episode)
    }

    // ====== Video List ======
    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url contains the video ID (e.g., "vid123")
        val url = "$apiBaseUrl/id/?id=${episode.url}"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val apiDetail = parseVideoDetailFromResponse(response)
        val files = apiDetail.files ?: return emptyList()
        return files.mapNotNull { file ->
            val quality = file.quality
            val videoUrl = file.url
            if (videoUrl.isNotBlank()) {
                Video(quality, quality, videoUrl, headers = headers)
            } else null
        }.sortedWith(
            compareByDescending<Video> { it.quality.extractQualityNumber() }
                .thenBy { it.quality }
        )
    }

    // ====== Helper Functions ======
    private fun parseApiResponse(response: Response): ApiResponse {
        val responseBody = response.awaitSuccess().use { it.body.string() }
        val jsonObject = json.parseToJsonElement(responseBody).jsonObject
        val videos = jsonObject["videos"]?.jsonArray?.mapNotNull { videoElement ->
            json.decodeFromJsonElement<ApiVideo>(videoElement)
        }
        val total = jsonObject["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val page = jsonObject["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val perpage = jsonObject["perpage"]?.jsonPrimitive?.content?.toIntOrNull() ?: 40
        return ApiResponse(videos, total, page, perpage)
    }

    private fun parseVideoDetailFromResponse(response: Response): ApiVideoDetail {
        val responseBody = response.awaitSuccess().use { it.body.string() }
        val jsonObject = json.parseToJsonElement(responseBody).jsonObject
        // The API returns {"video": {...}} for the /id/ endpoint
        val videoJson = jsonObject["video"] ?: error("No video object in response")
        return json.decodeFromJsonElement<ApiVideoDetail>(videoJson)
    }

    private fun ApiVideo.toSAnime(): SAnime? {
        return SAnime.create().apply {
            title = this@toSAnime.title
            url = baseUrl + this@toSAnime.url
            thumbnail_url = this@toSAnime.thumb?.src
            description = this@toSAnime.description
            artist = this@toSAnime.keywords
            status = SAnime.COMPLETED
        }
    }

    private fun parseDateToUnix(dateString: String): Long {
        // Eporner API returns date in format: "2025-01-15 14:30:22"
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Extract numeric quality from string like "1080p", "720p", "480p"
    private fun String.extractQualityNumber(): Int {
        val digits = filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits.toInt() else 0
    }
}
