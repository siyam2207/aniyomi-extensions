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
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString<ApiSearchResponse>(response.body.string())
            val animeList = apiResponse.videos.map { it.toSAnime() }
            // FIXED: Use correct property name
            val hasNextPage = apiResponse.page < apiResponse.totalPages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error", e)
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

        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&categories=$category&duration=$duration&quality=$quality&thumbsize=big&format=json"
        Log.d(tag, "Search URL: $url")
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            // FIXED: Uses the complete ApiVideoDetailResponse
            val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(response.body.string())
            videoDetail.toSAnime()
        } catch (e: Exception) {
            Log.e(tag, "API details failed: ${e.message}")
            // Fallback to a basic SAnime instead of HTML parsing
            SAnime.create().apply {
                url = response.request.url.toString()
                title = "Error loading details"
                status = SAnime.COMPLETED
            }
        }
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val videoId = anime.url.substringAfterLast("/").substringBefore("-")
        val url = "$apiUrl/video/id/?id=$videoId&format=json"
        return GET(url, headers)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers)
    }

    // ==================== NEW: Video List via Embed ====================
    override fun videoListRequest(episode: SEpisode): Request {
        // Get the video ID from the episode URL and build the EMBED URL
        val videoId = episode.url.substringAfterLast("/").substringBefore("-")
        val embedUrl = "https://www.eporner.com/embed/$videoId/"
        Log.d(tag, "Fetching embed page: $embedUrl")
        return GET(embedUrl, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()

            // NEW METHOD: Parse the embed page for video sources
            document.select("script").forEach { script ->
                val scriptText = script.html()
                // Pattern 1: Look for video sources array in JS
                val sourcesPattern = Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesPattern.find(scriptText)
                if (sourcesMatch != null) {
                    val sourcesJson = sourcesMatch.groupValues[1]
                    val videoPattern = Regex("""\{\s*"quality"\s*:\s*"(\d+)""?\s*,\s*"videoUrl"\s*:\s*"([^"]+)"\s*\}""")
                    videoPattern.findAll(sourcesJson).forEach { match ->
                        val quality = match.groupValues[1]
                        var videoUrl = match.groupValues[2].replace("\\/", "/") // Unescape slashes
                        // Some URLs might be relative
                        if (videoUrl.startsWith("//")) {
                            videoUrl = "https:" + videoUrl
                        } else if (videoUrl.startsWith("/")) {
                            videoUrl = "https://www.eporner.com" + videoUrl
                        }
                        if (videoUrl.isNotBlank() && (videoUrl.endsWith(".mp4") || videoUrl.contains(".mp4?"))) {
                            videos.add(Video(videoUrl, "Eporner - ${quality}p", videoUrl))
                            Log.d(tag, "Found video: ${quality}p - $videoUrl")
                        }
                    }
                }
                // Pattern 2: Direct quality/videoUrl pairs
                if (videos.isEmpty()) {
                    val directPattern = Regex("""quality["']?\s*:\s*["']?(\d+)""?["']?\s*,\s*videoUrl["']?\s*:\s*["']([^"']+)["']""")
                    directPattern.findAll(scriptText).forEach { match ->
                        val quality = match.groupValues[1]
                        var videoUrl = match.groupValues[2].replace("\\/", "/")
                        if (videoUrl.startsWith("//")) {
                            videoUrl = "https:" + videoUrl
                        } else if (videoUrl.startsWith("/")) {
                            videoUrl = "https://www.eporner.com" + videoUrl
                        }
                        if (videoUrl.isNotBlank() && (videoUrl.endsWith(".mp4") || videoUrl.contains(".mp4?"))) {
                            videos.add(Video(videoUrl, "Eporner - ${quality}p", videoUrl))
                        }
                    }
                }
            }

            Log.d(tag, "Total videos found in embed: ${videos.size}")
            videos.distinctBy { it.videoUrl }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error: ${e.message}", e)
            emptyList()
        }
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

    // ==================== UPDATED API Data Classes ====================
    @Serializable
    private data class ApiSearchResponse(
        val count: Int = 0,
        val start: Int = 0,
        @SerialName("per_page") val perPage: Int = 30,
        val page: Int = 1,
        @SerialName("time_ms") val timeMs: Int = 0,
        @SerialName("total_count") val totalCount: Long = 0,
        @SerialName("total_pages") val totalPages: Long = 1, // Changed to Long
        val videos: List<ApiVideo> = emptyList(),
    )

    @Serializable
    private data class ApiVideo(
        val id: String = "",
        val title: String = "",
        val keywords: String = "",
        val url: String = "",
        val views: Long = 0,
        val rate: String = "",
        val added: String = "",
        @SerialName("length_sec") val lengthSec: Int = 0,
        @SerialName("length_min") val lengthMin: String = "",
        val embed: String = "", // This is what we use for video extraction
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
        val thumbs: List<ApiThumbnail> = emptyList(),
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = when {
                url.isNotBlank() -> url
                id.isNotBlank() -> "https://www.eporner.com/video-$id/"
                else -> "https://www.eporner.com/"
            }
            this.title = if (title.isNotBlank()) title else "Untitled Video"
            this.thumbnail_url = defaultThumb.src
            this.genre = keywords
            status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiVideoDetailResponse(
        val id: String = "",
        val title: String = "",
        val keywords: String = "",
        val url: String = "",
        val views: Long = 0,
        val rate: String = "",
        val added: String = "",
        @SerialName("length_sec") val lengthSec: Int = 0,
        @SerialName("length_min") val lengthMin: String = "",
        val embed: String = "",
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
        val thumbs: List<ApiThumbnail> = emptyList(),
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = when {
                url.isNotBlank() -> url
                id.isNotBlank() -> "https://www.eporner.com/video-$id/"
                else -> "https://www.eporner.com/"
            }
            this.title = if (title.isNotBlank()) title else "Unknown Title"
            this.thumbnail_url = defaultThumb.src
            this.genre = keywords
            description = "Views: $views | Length: ${lengthSec / 60}min | Rating: $rate"
            status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiThumbnail(
        val src: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size: String = "",
    )
}
