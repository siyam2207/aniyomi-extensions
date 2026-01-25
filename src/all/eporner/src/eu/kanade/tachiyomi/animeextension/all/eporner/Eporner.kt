package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Eporner : AnimeHttpSource() {
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val tag = "EpornerExtension"

    // ==================== Popular Anime ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=most-popular&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString<ApiSearchResponse>(response.body.string())
            val animeList = apiResponse.videos.map { it.toSAnime(baseUrl) }
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error: ${e.message}")
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== Latest Updates ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Search ====================
    override fun searchAnimeRequest(page: Int, query: String): Request {
        return if (query.isNotBlank()) {
            val url = "$apiUrl/video/search/?query=${query.encode()}&page=$page&thumbsize=big&format=json"
            GET(url, headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val videoDetail = json.decodeFromString<ApiVideoResponse>(response.body.string())
            videoDetail.toSAnime(baseUrl)
        } catch (e: Exception) {
            SAnime.create().apply {
                title = "Error loading details"
                status = SAnime.COMPLETED
            }
        }
    }

    // ==================== Episodes ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
            },
        )
    }

    // ==================== Video Extraction ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Try to find HLS master playlist
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val hlsRegex = Regex("""(https?:[^"' ]+\.m3u8[^"' ]*)""")
            hlsRegex.findAll(scriptText).forEach { match ->
                val url = match.value
                if (url.contains("m3u8")) {
                    try {
                        val playlistUtils = PlaylistUtils(client, headers)
                        videos.addAll(playlistUtils.extractFromHls(url, response.request.url.toString()))
                    } catch (e: Exception) {
                        Log.e(tag, "HLS extraction failed: ${e.message}")
                    }
                }
            }
        }

        // Fallback: Direct video source
        if (videos.isEmpty()) {
            document.select("video source").forEach { source ->
                val url = source.attr("abs:src")
                val quality = source.attr("label").ifEmpty { "Source" }
                if (url.isNotBlank()) {
                    videos.add(Video(url, quality, url))
                }
            }
        }

        return videos.distinctBy { it.url }
    }

    // ==================== Utility Functions ====================
    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // ==================== Data Classes ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
    )

    @Serializable
    private data class ApiVideoResponse(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("views") val views: Long,
        @SerialName("url") val url: String,
        @SerialName("added") val added: String,
        @SerialName("length_sec") val lengthSec: Int,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
        @SerialName("thumbs") val thumbs: List<ApiThumbnail>,
    ) {
        fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
            setUrlWithoutDomain(this@ApiVideoResponse.url)
            this.title = this@ApiVideoResponse.title
            this.thumbnail_url = this@ApiVideoResponse.defaultThumb.src
            this.genre = this@ApiVideoResponse.keywords
            this.description = "Views: $views | Length: ${lengthSec / 60}min"
            this.status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
            setUrlWithoutDomain(this@ApiVideo.url)
            this.title = this@ApiVideo.title
            this.thumbnail_url = this@ApiVideo.defaultThumb.src
            this.genre = this@ApiVideo.keywords
            this.status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )
}
