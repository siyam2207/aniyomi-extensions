package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()
    private val tag = "EpornerExtension"

    // Configure JSON to ignore unknown keys
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ==================== Headers ====================
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Accept", "application/json, text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Referer", baseUrl)
            .add("Origin", baseUrl)
    }

    // ==================== Popular Anime ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Popular request: $url")
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val jsonString = response.body.string()
            Log.d(tag, "Popular response length: ${jsonString.length}")
            val apiResponse = jsonParser.decodeFromString<ApiSearchResponse>(jsonString)
            Log.d(tag, "Found ${apiResponse.videos.size} videos")
            
            val animeList = apiResponse.videos.map { it.toSAnime() }
            
            // Log first video details for debugging
            if (animeList.isNotEmpty()) {
                Log.d(tag, "First anime title: ${animeList[0].title}, URL: ${animeList[0].url}")
            }
            
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error: ${e.message}", e)
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== Latest Updates ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Latest request: $url")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Search ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = if (query.isNotBlank()) {
            URLEncoder.encode(query, "UTF-8")
        } else {
            "all"
        }

        // Note: The API documentation doesn't show category, duration, quality filters in the search params
        // They only show: query, per_page, page, thumbsize, order, gay, lq, format
        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Search URL: $url")
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val jsonString = response.body.string()
            Log.d(tag, "Details response: $jsonString")
            
            // Try to parse as array first (ID API returns array)
            val videos = jsonParser.decodeFromString<List<ApiVideoDetailResponse>>(jsonString)
            
            if (videos.isNotEmpty()) {
                videos[0].toSAnime()
            } else {
                // Try parsing as single object
                val videoDetail = jsonParser.decodeFromString<ApiVideoDetailResponse>(jsonString)
                videoDetail.toSAnime()
            }
        } catch (e: Exception) {
            Log.e(tag, "API details failed: ${e.message}", e)
            
            // Fallback to HTML parsing
            try {
                val document = response.asJsoup()
                SAnime.create().apply {
                    url = response.request.url.toString()
                    title = document.selectFirst("h1")?.text() ?: "Unknown Title"
                    thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
                        ?: document.selectFirst("img.thumb")?.attr("src")

                    val viewsText = document.selectFirst("div.views")?.text() ?: ""
                    val durationText = document.selectFirst("div.length")?.text() ?: ""

                    description = buildString {
                        if (viewsText.isNotEmpty()) append("Views: $viewsText\n")
                        if (durationText.isNotEmpty()) append("Duration: $durationText\n")

                        val tags = document.select("a.tag").map { it.text() }
                        if (tags.isNotEmpty()) {
                            append("Tags: ${tags.joinToString(", ")}")
                        }
                    }

                    genre = document.select("a.tag").map { it.text() }.joinToString(", ")
                    status = SAnime.COMPLETED
                }
            } catch (e2: Exception) {
                Log.e(tag, "HTML details parse error: ${e2.message}")
                SAnime.create().apply {
                    title = "Error loading details"
                    status = SAnime.COMPLETED
                }
            }
        }
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        // Extract video ID from URL
        // URL format: https://www.eporner.com/hd-porn/IsabYDAiqXa/Young-Teen-Heather/
        val videoId = anime.url
            .removeSuffix("/")
            .substringAfterLast("/")
            .substringBefore("-")
        
        if (videoId.contains("/")) {
            // Try alternative extraction
            val segments = anime.url.split("/")
            for (segment in segments.reversed()) {
                if (segment.isNotBlank() && segment.length > 5) {
                    val possibleId = segment
                    Log.d(tag, "Trying video ID: $possibleId")
                    val url = "$apiUrl/video/id/?id=$possibleId&format=json"
                    return GET(url, headers)
                }
            }
        }
        
        Log.d(tag, "Extracted video ID: $videoId from URL: ${anime.url}")
        val url = "$apiUrl/video/id/?id=$videoId&format=json"
        return GET(url, headers)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers)
    }

    // ==================== Episodes ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Full Video"
                episode_number = 1F
                url = response.request.url.toString()
                date_upload = System.currentTimeMillis()
            },
        )
    }

    // ==================== Video Extraction ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()

            // METHOD 1: Look for hlsPlayer data
            document.select("script").forEach { script ->
                val scriptText = script.html()
                
                // Try to find HLS playlist
                val hlsPattern = Regex("""hlsUrl["']?\s*:\s*["']([^"']+)["']""")
                hlsPattern.findAll(scriptText).forEach { match ->
                    val hlsUrl = match.groupValues[1]
                    if (hlsUrl.isNotBlank() && hlsUrl.contains("m3u8")) {
                        try {
                            Log.d(tag, "Found HLS URL: $hlsUrl")
                            val playlistUtils = PlaylistUtils(client, headers)
                            val hlsVideos = playlistUtils.extractFromHls(
                                hlsUrl,
                                response.request.url.toString(),
                                videoNameGen = { quality -> "Stream - $quality" },
                            )
                            videos.addAll(hlsVideos)
                        } catch (e: Exception) {
                            Log.e(tag, "HLS extraction failed: ${e.message}")
                        }
                    }
                }

                // Try to find MP4 sources
                val mp4Pattern = Regex("""videoUrl["']?\s*:\s*["']([^"']+)["']""")
                mp4Pattern.findAll(scriptText).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && videoUrl.contains("mp4")) {
                        val quality = extractQualityFromUrl(videoUrl)
                        videos.add(Video(videoUrl, "MP4 - $quality", videoUrl))
                        Log.d(tag, "Found MP4: $quality - $videoUrl")
                    }
                }
            }

            // METHOD 2: Look for video sources in HTML
            document.select("source[src]").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank() && (src.contains("mp4") || src.contains("m3u8"))) {
                    val quality = source.attr("label") ?: extractQualityFromUrl(src)
                    videos.add(Video(src, "Source - $quality", src))
                }
            }

            // METHOD 3: Look for iframe embeds
            document.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.contains("embed")) {
                    videos.add(Video(src, "Embed", src))
                }
            }

            Log.d(tag, "Total videos found: ${videos.size}")
            
            // Sort by quality
            videos.sortedByDescending {
                when {
                    it.quality.contains("1080") -> 1080
                    it.quality.contains("720") -> 720
                    it.quality.contains("480") -> 480
                    it.quality.contains("360") -> 360
                    it.quality.contains("240") -> 240
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Unknown"
        }
    }

    // ==================== Settings ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default sort order"
            entries = SORT_LIST
            entryValues = SORT_LIST
            setDefaultValue(PREF_SORT_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> {
                when {
                    qualityPref == "best" -> it.quality.replace("p", "").toIntOrNull() ?: 0
                    it.quality.contains(qualityPref) -> 1000
                    else -> 0
                }
            },
        )
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Note: Some filters may not work with API"),
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p")

        private const val PREF_SORT_KEY = "default_sort"
        private const val PREF_SORT_DEFAULT = "top-weekly"
        private val SORT_LIST = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated")
    }

    // ==================== API Data Classes ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
        @SerialName("count") val count: Int,
        @SerialName("total_count") val total_count: Long,
    )

    @Serializable
    private data class ApiVideoDetailResponse(
        @SerialName("id") val id: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("keywords") val keywords: String = "",
        @SerialName("views") val views: Long = 0,
        @SerialName("rate") val rate: String = "",
        @SerialName("url") val url: String = "",
        @SerialName("added") val added: String = "",
        @SerialName("length_sec") val lengthSec: Int = 0,
        @SerialName("length_min") val lengthMin: String = "",
        @SerialName("embed") val embed: String = "",
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
        @SerialName("thumbs") val thumbs: List<ApiThumbnail> = emptyList(),
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = this@ApiVideoDetailResponse.url
            this.title = this@ApiVideoDetailResponse.title
            this.thumbnail_url = this@ApiVideoDetailResponse.defaultThumb.src
            this.genre = this@ApiVideoDetailResponse.keywords
            this.description = buildString {
                append("Views: ${views}\n")
                append("Length: ${lengthMin}\n")
                append("Rating: ${rate}/5.0\n")
                append("Added: ${added}")
            }
            this.status = SAnime.COMPLETED
            this.artist = ""
            this.author = ""
        }
    }

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("keywords") val keywords: String = "",
        @SerialName("url") val url: String = "",
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
        @SerialName("views") val views: Long = 0,
        @SerialName("rate") val rate: String = "",
        @SerialName("added") val added: String = "",
        @SerialName("length_sec") val lengthSec: Int = 0,
        @SerialName("length_min") val lengthMin: String = "",
        @SerialName("embed") val embed: String = "",
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = this@ApiVideo.url
            this.title = this@ApiVideo.title
            this.thumbnail_url = this@ApiVideo.defaultThumb.src
            this.genre = this@ApiVideo.keywords
            this.description = buildString {
                append("Views: ${views}\n")
                append("Length: ${lengthMin}\n")
                append("Rating: ${rate}/5.0")
            }
            this.status = SAnime.COMPLETED
            this.artist = ""
            this.author = ""
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String = "",
        @SerialName("width") val width: Int = 0,
        @SerialName("height") val height: Int = 0,
        @SerialName("size") val size: String = "",
    )
}
