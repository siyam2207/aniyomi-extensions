package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
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

    // Correct API base – note the /video/ segment
    private val apiBaseUrl = "https://www.eporner.com/api/v2/video"
    private val json: Json by injectLazy()

    companion object {
        private const val TAG = "Eporner"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }

    // =========================================================================
    // Data classes matching the actual API responses
    // =========================================================================

    /** Response from /search/ endpoint */
    @Serializable
    data class ApiSearchResponse(
        val videos: List<ApiVideo>?,
        val total_count: Int,
        val page: Int,
        val per_page: Int,
        val total_pages: Int
    )

    /** A single video as returned by search or id endpoints */
    @Serializable
    data class ApiVideo(
        val id: String,
        val title: String,
        val description: String? = null,
        val keywords: String? = null,
        val views: Int,
        val rate: String,
        val url: String,                 // Full URL to the video page
        val added: String,                // Date string "YYYY-MM-DD HH:MM:SS"
        val length_sec: Int,
        val default_thumb: ThumbDetails?  // Used for listing
    )

    @Serializable
    data class ThumbDetails(
        val src: String
    )

    /** Response from /id/ endpoint (wraps a single video) */
    @Serializable
    data class ApiIdResponse(
        val video: ApiVideo
    )

    // =========================================================================
    // Popular / Latest / Search (all use the search endpoint)
    // =========================================================================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "most-popular")   // Correct value!
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val apiResponse = parseSearchResponse(response)
        val animeList = apiResponse.videos?.mapNotNull { it.toSAnime() } ?: emptyList()
        // Pagination: use total_pages
        val hasNextPage = apiResponse.page < apiResponse.total_pages
        return AnimesPage(animeList, hasNextPage)
    }

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", query.ifEmpty { "all" })
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
        // Optional: add filter parameters (gay, lq, thumbsize) if you implement filters
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // =========================================================================
    // Anime Details – uses the /id/ endpoint
    // =========================================================================

    override fun animeDetailsRequest(anime: SAnime): Request {
        // Extract video ID from the full URL stored in anime.url
        // Example URL: https://www.eporner.com/hd-porn/IsabYDAiqXa/Young-Teen-Heather/
        val videoId = anime.url.substringAfter("/hd-porn/").substringBefore("/")
        val url = "$apiBaseUrl/id/?id=$videoId&format=json"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val apiVideo = parseIdResponse(response).video
        return SAnime.create().apply {
            url = apiVideo.url          // Keep the full URL
            title = apiVideo.title
            thumbnail_url = apiVideo.default_thumb?.src
            description = buildDescription(apiVideo)
            artist = apiVideo.keywords
            status = SAnime.COMPLETED
            // You could also store views/rate in a custom field if desired
        }
    }

    // =========================================================================
    // Episode List – one episode per video
    // =========================================================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        // We already have the video details from the previous call
        val apiVideo = parseIdResponse(response).video
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1f
            // Store the full video page URL – we'll need it to scrape the actual video sources
            url = apiVideo.url
            date_upload = parseDateToUnix(apiVideo.added)
        }
        return listOf(episode)
    }

    // =========================================================================
    // Video List – scrape the video page for actual stream URLs
    // =========================================================================

    override fun videoListRequest(episode: SEpisode): Request {
        // episode.url is the full video page URL (e.g., https://www.eporner.com/hd-porn/IsabYDAiqXa/...)
        return GET(episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.awaitSuccess().use { Jsoup.parse(it.body.string()) }
        val videos = mutableListOf<Video>()

        // Strategy 1: Look for a JSON object inside a script tag that contains video sources.
        // Many sites embed a player configuration like:
        //   var player = {"sources": [{"src": "...", "type": "..."}, ...]}
        // We'll search for patterns that look like a sources array.

        val scripts = document.select("script").eachText()
        for (script in scripts) {
            // Try to find a JSON-like structure containing "sources"
            if (script.contains("sources") && script.contains("video") && script.contains("src")) {
                // Extract a JSON object – this is heuristic
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

        // Strategy 2: Fallback – look for direct video URLs in the HTML (less common)
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

        // Strategy 3: If still empty, log the page for debugging
        if (videos.isEmpty()) {
            Log.w(TAG, "No video sources found on page: ${response.request.url}")
        }

        // Sort by quality (descending)
        return videos.sortedWith(
            compareByDescending<Video> { it.quality.extractQualityNumber() }
                .thenBy { it.quality }
        )
    }

    /**
     * Attempt to extract a JSON object from a script block.
     * Looks for the first '{' and the matching closing '}'.
     */
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

    /**
     * Parse a JSON string that might contain video sources.
     * This is tailored to common patterns; you may need to adjust.
     */
    private fun extractVideoFromJson(jsonStr: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val element = json.parseToJsonElement(jsonStr)
            // Try to find a "sources" array
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
            Log.e(TAG, "Failed to parse JSON for video sources", e)
        }
        return videos
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    /** Parse the search endpoint response (handles empty body gracefully) */
    private fun parseSearchResponse(response: Response): ApiSearchResponse {
        val responseBody = response.awaitSuccess().use { it.body.string() }
        if (responseBody.isBlank()) {
            return ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
        return try {
            json.decodeFromString<ApiSearchResponse>(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search response: ${e.message}")
            ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
    }

    /** Parse the /id/ endpoint response */
    private fun parseIdResponse(response: Response): ApiIdResponse {
        val responseBody = response.awaitSuccess().use { it.body.string() }
        return json.decodeFromString<ApiIdResponse>(responseBody)
    }

    /** Convert an ApiVideo to an SAnime for listing */
    private fun ApiVideo.toSAnime(): SAnime {
        return SAnime.create().apply {
            title = this@toSAnime.title
            url = this@toSAnime.url          // Full URL already
            thumbnail_url = this@toSAnime.default_thumb?.src
            description = this@toSAnime.keywords ?: this@toSAnime.description
            artist = this@toSAnime.keywords
            status = SAnime.COMPLETED
        }
    }

    /** Build a nicer description from available fields */
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

    /** Convert date string "YYYY-MM-DD HH:MM:SS" to Unix timestamp (milliseconds) */
    private fun parseDateToUnix(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Extract numeric part from quality string (e.g., "1080p" -> 1080) */
    private fun String.extractQualityNumber(): Int {
        val digits = filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits.toInt() else 0
    }
}
