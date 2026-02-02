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
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val body = response.body.string()
            Log.d(tag, "Popular API response length: ${body.length}")
            val apiResponse = json.decodeFromString<ApiSearchResponse>(body)
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            Log.d(tag, "Parsed ${animeList.size} anime, hasNext: $hasNextPage")
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error", e)
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== Latest Updates ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
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

        var category = "all"
        var duration = "0"
        var quality = "0"

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state != 0) category = filter.toUriPart()
                }
                is DurationFilter -> {
                    if (filter.state != 0) duration = filter.toUriPart()
                }
                is QualityFilter -> {
                    if (filter.state != 0) quality = filter.toUriPart()
                }
                is AnimeFilter.Header,
                is AnimeFilter.Separator,
                is AnimeFilter.Group<*>,
                is AnimeFilter.CheckBox,
                is AnimeFilter.TriState,
                is AnimeFilter.Select<*>,
                is AnimeFilter.Text,
                is AnimeFilter.Sort,
                -> {
                    // Do nothing for these filter types
                }
            }
        }

        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&categories=$category&duration=$duration&quality=$quality&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Search URL: $url")
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        Log.d(tag, "Details request for URL: ${anime.url}")
        
        // Try to extract video ID from URL for API call
        val videoId = try {
            // Handle both formats: /video/ID/title/ and full URL
            val path = if (anime.url.startsWith("http")) {
                anime.url.substringAfter(baseUrl)
            } else {
                anime.url
            }
            path.substringAfter("/video/").substringBefore("/").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(tag, "Could not extract video ID from URL: ${anime.url}", e)
            null
        }

        return if (videoId != null && videoId.length > 5) {
            // Use API for details
            val url = "$apiUrl/video/id/?id=$videoId&format=json"
            Log.d(tag, "Using API URL: $url")
            GET(url, headers)
        } else {
            // Fall back to HTML page
            val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
            Log.d(tag, "Using HTML URL: $url")
            GET(url, headers)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val body = response.body.string()
            Log.d(tag, "Details response length: ${body.length}")
            
            // Try to parse as API response first
            if (body.startsWith("{") && body.contains("\"id\"")) {
                try {
                    val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(body)
                    Log.d(tag, "Successfully parsed API response for ID: ${videoDetail.id}")
                    return videoDetail.toSAnime()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to parse API response, trying HTML: ${e.message}")
                }
            }
            
            // Fall back to HTML parsing
            htmlAnimeDetailsParse(response)
        } catch (e: Exception) {
            Log.e(tag, "Details parse error", e)
            htmlAnimeDetailsParse(response)
        }
    }

    private fun htmlAnimeDetailsParse(response: Response): SAnime {
        return try {
            val document = response.asJsoup()
            SAnime.create().apply {
                url = response.request.url.toString()
                
                // ALWAYS assign title - never leave lateinit var uninitialized
                title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: "Unknown Title"
                
                // Safe thumbnail assignment (can be null)
                thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("img.thumb")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }

                val viewsText = document.selectFirst("div.views")?.text()?.takeIf { it.isNotBlank() } ?: ""
                val durationText = document.selectFirst("div.length")?.text()?.takeIf { it.isNotBlank() } ?: ""

                description = buildString {
                    if (viewsText.isNotEmpty()) append("Views: $viewsText\n")
                    if (durationText.isNotEmpty()) append("Duration: $durationText\n")

                    val tags = document.select("a.tag").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }
                    if (tags.isNotEmpty()) {
                        append("Tags: ${tags.joinToString(", ")}")
                    }
                    if (isEmpty()) append("No description available")
                }

                val tags = document.select("a.tag").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }
                genre = if (tags.isNotEmpty()) tags.joinToString(", ") else null

                status = SAnime.COMPLETED
                
                Log.d(tag, "HTML parsed: title='$title', thumbnail=${thumbnail_url != null}, genre=${genre != null}")
            }
        } catch (e: Exception) {
            Log.e(tag, "HTML details parse error", e)
            SAnime.create().apply {
                title = "Unknown Title"
                status = SAnime.COMPLETED
                description = "Failed to load details"
            }
        }
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
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
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString()
            },
        )
    }

    // ==================== Video Extraction (Enhanced) ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()

            // METHOD 1: Eporner JavaScript pattern
            document.select("script").forEach { script ->
                val scriptText = script.html()
                // Look for quality and videoUrl patterns
                val patterns = listOf(
                    Regex("""quality["']?\s*:\s*["']?(\d+)["']?\s*,\s*videoUrl["']?\s*:\s*["']([^"']+)["']"""),
                    Regex("""videoUrl["']?\s*:\s*["']([^"']+)["']\s*,\s*quality["']?\s*:\s*["']?(\d+)["']?"""),
                    Regex("""['"]src['"]\s*:\s*['"]([^"']+\.mp4)['"]"""),
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val quality = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "unknown"
                        val videoUrl = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                        
                        if (videoUrl != null && videoUrl.isNotBlank()) {
                            val displayQuality = if (quality == "unknown") {
                                videoUrl.extractQualityFromUrl()
                            } else {
                                "${quality}p"
                            }
                            videos.add(Video(videoUrl, "Eporner - $displayQuality", videoUrl))
                            Log.d(tag, "Found video: $displayQuality")
                        }
                    }
                }
            }

            // METHOD 2: HLS streams
            if (videos.isEmpty()) {
                document.select("script").forEach { script ->
                    val scriptText = script.html()
                    val hlsPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                    hlsPattern.findAll(scriptText).forEach { match ->
                        val url = match.value
                        if (url.contains(".m3u8")) {
                            try {
                                val playlistUtils = PlaylistUtils(client, headers)
                                val hlsVideos = playlistUtils.extractFromHls(
                                    url,
                                    response.request.url.toString(),
                                    videoNameGen = { quality -> "HLS - $quality" },
                                )
                                videos.addAll(hlsVideos)
                                Log.d(tag, "Found ${hlsVideos.size} HLS streams")
                            } catch (e: Exception) {
                                Log.e(tag, "HLS extraction failed", e)
                            }
                        }
                    }
                }
            }

            // METHOD 3: Direct MP4 fallback
            if (videos.isEmpty()) {
                val html = document.html()
                val mp4Patterns = listOf(
                    Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']"""),
                    Regex("""(https?://[^"'\s]+\.mp4)"""),
                )

                mp4Patterns.forEach { pattern ->
                    pattern.findAll(html).forEach { match ->
                        val url = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value.takeIf { it.isNotBlank() }
                        if (url != null && url.startsWith("http") && !videos.any { it.videoUrl == url }) {
                            val quality = url.extractQualityFromUrl()
                            videos.add(Video(url, "Direct - $quality", url))
                            Log.d(tag, "Found MP4: $quality")
                        }
                    }
                }
            }

            Log.d(tag, "Total videos found: ${videos.size}")
            videos.distinctBy { it.videoUrl }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error", e)
            emptyList()
        }
    }

    private fun String.extractQualityFromUrl(): String = when {
        contains("1080") -> "1080p"
        contains("720") -> "720p"
        contains("480") -> "480p"
        contains("360") -> "360p"
        contains("240") -> "240p"
        else -> "Unknown"
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
        CategoryFilter(),
        DurationFilter(),
        QualityFilter(),
    )

    // ==================== Filter Classes ====================
    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter : UriPartFilter(
        "Category",
        arrayOf(
            Pair("All", "all"),
            Pair("Anal", "anal"),
            Pair("Asian", "asian"),
            Pair("Big Dick", "big-dick"),
            Pair("Blowjob", "blowjob"),
            Pair("Brunette", "brunette"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Doggystyle", "doggystyle"),
            Pair("Ebony", "ebony"),
            Pair("Facial", "facial"),
            Pair("Gangbang", "gangbang"),
            Pair("HD", "hd"),
            Pair("Interracial", "interracial"),
            Pair("Lesbian", "lesbian"),
            Pair("Masturbation", "masturbation"),
            Pair("Mature", "mature"),
            Pair("Milf", "milf"),
            Pair("Teen", "teen"),
        ),
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            Pair("Any", "0"),
            Pair("10+ min", "10"),
            Pair("20+ min", "20"),
            Pair("30+ min", "30"),
        ),
    )

    private class QualityFilter : UriPartFilter(
        "Quality",
        arrayOf(
            Pair("Any", "0"),
            Pair("HD 1080", "1080"),
            Pair("HD 720", "720"),
            Pair("HD 480", "480"),
        ),
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
    )

    @Serializable
    private data class ApiVideoDetailResponse(
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
        fun toSAnime(): SAnime = SAnime.create().apply {
            // ALWAYS assign title - never leave lateinit var uninitialized
            this.title = this@ApiVideoDetailResponse.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            
            // URL is already full URL from API
            this.url = this@ApiVideoDetailResponse.url
            
            // Safe thumbnail (can be null)
            this.thumbnail_url = this@ApiVideoDetailResponse.defaultThumb.src.takeIf { it.isNotBlank() }
            
            // Genre can be null
            this.genre = this@ApiVideoDetailResponse.keywords.takeIf { it.isNotBlank() }
            
            // ALWAYS assign description
            val lengthMin = this@ApiVideoDetailResponse.lengthSec / 60
            val lengthSec = this@ApiVideoDetailResponse.lengthSec % 60
            this.description = "Views: ${this@ApiVideoDetailResponse.views} | Length: ${lengthMin}m ${lengthSec}s"
            
            this.status = SAnime.COMPLETED
            
            Log.d(tag, "API parsed: title='$title', views=${this@ApiVideoDetailResponse.views}")
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
        fun toSAnime(): SAnime = SAnime.create().apply {
            // ALWAYS assign title - never leave lateinit var uninitialized
            this.title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            
            // Ensure URL is compatible with details request
            // API returns relative URL like "/video/abc123/title/"
            // Convert to full URL that can be used for both API and HTML requests
            val relativeUrl = this@ApiVideo.url
            this.url = if (relativeUrl.startsWith("http")) {
                relativeUrl
            } else if (relativeUrl.startsWith("/")) {
                "$baseUrl$relativeUrl"
            } else {
                "$baseUrl/$relativeUrl"
            }
            
            // Safe thumbnail (can be null)
            this.thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }
            
            // Genre can be null
            this.genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }
            
            this.status = SAnime.COMPLETED
            
            Log.d(tag, "Search result: title='$title', url='$url'")
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )
}
