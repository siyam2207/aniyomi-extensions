package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val apiBaseUrl = "https://www.eporner.com/api/v2/video"
    private val json: Json by injectLazy()

    // ====== API Data Classes ======
    @Serializable
    data class ApiSearchResponse(
        val videos: List<ApiVideo>?,
        val total_count: Int,
        val page: Int,
        val per_page: Int,
        val total_pages: Int,
    )

    @Serializable
    data class ApiVideo(
        val id: String,
        val title: String,
        val description: String? = null,
        val keywords: String? = null,
        val views: Int,
        val rate: String,
        val url: String,
        val added: String,
        val length_sec: Int,
        val default_thumb: ThumbDetails? = null,
    )

    @Serializable
    data class ThumbDetails(
        val src: String,
    )

    @Serializable
    data class ApiIdResponse(
        val video: ApiVideo,
    )

    // ====== Popular ======
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "most-popular")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val apiResponse = parseSearchResponse(response)
        val animeList = apiResponse.videos?.mapNotNull { it.toSAnime() } ?: emptyList()
        val hasNextPage = apiResponse.page < apiResponse.total_pages
        return AnimesPage(animeList, hasNextPage)
    }

    // ====== Latest ======
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ====== Search ======
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", query.ifEmpty { "all" })
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====== Anime Details ======
    override fun animeDetailsRequest(anime: SAnime): Request {
        val videoId = anime.url.substringAfter("/hd-porn/").substringBefore("/")
        val url = "$apiBaseUrl/id/?id=$videoId&format=json"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val apiVideo = parseIdResponse(response).video
        return SAnime.create().apply {
            url = ensureAbsoluteUrl(apiVideo.url)
            title = apiVideo.title
            thumbnail_url = apiVideo.default_thumb?.src
            description = buildDescription(apiVideo)
            artist = apiVideo.keywords
            status = SAnime.COMPLETED
        }
    }

    // ====== Episode List ======
    override fun episodeListParse(response: Response): List<SEpisode> {
        val apiVideo = parseIdResponse(response).video
        // Use the embed page URL for video extraction
        val embedUrl = buildEmbedUrl(apiVideo.id)
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1f
            url = embedUrl // store embed URL
            date_upload = parseDateToUnix(apiVideo.added)
        }
        return listOf(episode)
    }

    // ====== Video List ======
    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url is the embed page URL
        return GET(episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        val videos = mutableListOf<Video>()

        // Look for a script tag containing the player configuration
        val scripts = document.select("script").eachText()
        for (script in scripts) {
            if (script.contains("sources") && script.contains("src")) {
                val jsonCandidate = extractJsonObject(script)
                if (jsonCandidate != null) {
                    val extracted = extractVideoFromJson(jsonCandidate)
                    if (extracted.isNotEmpty()) {
                        videos.addAll(extracted)
                        break
                    }
                }
            }
        }

        // Fallback: look for direct video source tags (rare on embed page)
        if (videos.isEmpty()) {
            val videoElements = document.select("video source[src], source[src*=.mp4], source[src*=.m3u8]")
            videoElements.forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val quality = element.attr("label").ifEmpty { "Unknown" }
                    videos.add(Video(src, quality, src, headers = headers))
                }
            }
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.quality.extractQualityNumber() }
                .thenBy { it.quality },
        )
    }

    // ====== Helper Functions ======
    private fun parseSearchResponse(response: Response): ApiSearchResponse {
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("application/json")) {
            return ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
        val responseBody = response.body.string()
        if (responseBody.isBlank()) {
            return ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
        return try {
            json.decodeFromString<ApiSearchResponse>(responseBody)
        } catch (e: Exception) {
            ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
    }

    private fun parseIdResponse(response: Response): ApiIdResponse {
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("application/json")) {
            return ApiIdResponse(ApiVideo("", "", url = ""))
        }
        val responseBody = response.body.string()
        return try {
            json.decodeFromString<ApiIdResponse>(responseBody)
        } catch (e: Exception) {
            ApiIdResponse(ApiVideo("", "", url = ""))
        }
    }

    private fun ApiVideo.toSAnime(): SAnime {
        return SAnime.create().apply {
            title = this@toSAnime.title
            url = ensureAbsoluteUrl(this@toSAnime.url)
            thumbnail_url = this@toSAnime.default_thumb?.src
            description = this@toSAnime.keywords ?: this@toSAnime.description
            artist = this@toSAnime.keywords
            status = SAnime.COMPLETED
        }
    }

    private fun ensureAbsoluteUrl(url: String): String {
        return if (url.startsWith("http")) url else baseUrl + url
    }

    private fun buildEmbedUrl(videoId: String): String {
        return "https://www.eporner.com/embed/$videoId/"
    }

    private fun buildDescription(video: ApiVideo): String {
        val parts = mutableListOf<String>()
        if (!video.description.isNullOrBlank()) {
            parts.add(video.description)
        }
        if (!video.keywords.isNullOrBlank()) {
            parts.add("\n\nTags: ${video.keywords}")
        }
        parts.add("\n\nViews: ${video.views} | Rating: ${video.rate}")
        return parts.joinToString("")
    }

    private fun parseDateToUnix(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun String.extractQualityNumber(): Int {
        val digits = filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits.toInt() else 0
    }

    private fun extractJsonObject(text: String): String? {
        var start = text.indexOf('{')
        if (start == -1) return null
        var braceCount = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (!inString) {
                when (c) {
                    '{' -> braceCount++
                    '}' -> braceCount--
                    '"' -> inString = true
                }
            } else {
                when (c) {
                    '"' -> if (!escape) inString = false
                    '\\' -> escape = !escape
                    else -> escape = false
                }
            }
            if (braceCount == 0) {
                return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractVideoFromJson(jsonStr: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val element = json.parseToJsonElement(jsonStr)
            // Look for sources array at various common paths
            val sources = element.jsonObject["sources"]?.jsonArray
                ?: element.jsonObject["video"]?.jsonObject?.get("sources")?.jsonArray
                ?: element.jsonObject["playlist"]?.jsonArray?.firstOrNull()?.jsonObject?.get("sources")?.jsonArray
            if (sources != null) {
                for (srcElement in sources) {
                    val srcObj = srcElement.jsonObject
                    val src = srcObj["src"]?.jsonPrimitive?.content
                    val type = srcObj["type"]?.jsonPrimitive?.content
                    val label = srcObj["label"]?.jsonPrimitive?.content ?: srcObj["quality"]?.jsonPrimitive?.content
                    if (!src.isNullOrBlank()) {
                        val quality = label ?: when {
                            type?.contains("m3u8") == true -> "HLS"
                            else -> "Unknown"
                        }
                        videos.add(Video(src, quality, src, headers = headers))
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return videos
    }
}
