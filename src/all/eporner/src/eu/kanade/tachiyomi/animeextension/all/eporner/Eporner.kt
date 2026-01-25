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
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiBaseUrl = "$baseUrl/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json by injectLazy<kotlinx.serialization.json.Json>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ==============================
    // 1. POPULAR ANIME
    // ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "top-weekly")
            .addQueryParameter("thumbsize", "medium")
            .addQueryParameter("format", "json")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 2. LATEST UPDATES
    // ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "latest")
            .addQueryParameter("thumbsize", "medium")
            .addQueryParameter("format", "json")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 3. SEARCH
    // ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "30")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("thumbsize", "medium")
            .addQueryParameter("format", "json")

        var searchQuery = query
        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state != 0) {
                        searchQuery = if (searchQuery.isBlank()) filter.values[filter.state] 
                                    else "$searchQuery ${filter.values[filter.state]}"
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("order", filter.values[filter.state])
                    }
                }
                is GayFilter -> {
                    url.addQueryParameter("gay", filter.state.toString())
                }
                else -> {}
            }
        }

        url.addQueryParameter("query", searchQuery.ifBlank { "all" })
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 4. ANIME DETAILS
    // ==============================
    override fun animeDetailsParse(response: Response): SAnime {
        val video = response.parseAs<ApiVideoSearchResponse>().videos.firstOrNull()
            ?: throw Exception("Failed to parse video details")

        return SAnime.create().apply {
            title = video.title
            thumbnail_url = video.defaultThumb.src
            genre = video.keywords.split(", ").joinToString()
            description = buildString {
                append("Rating: ${video.rate}\n")
                append("Views: ${video.views}\n")
                append("Duration: ${video.lengthMin}\n")
                append("Added: ${video.added}\n")
                append("\nTags: ${video.keywords}")
            }
            status = SAnime.COMPLETED
            setUrlWithoutDomain(video.url)
        }
    }

    // ==============================
    // 5. EPISODE LIST
    // ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val video = response.parseAs<ApiVideoSearchResponse>().videos.firstOrNull()
            ?: return emptyList()
            
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain("$baseUrl/embed/${video.id}")
                date_upload = runCatching {
                    dateFormat.parse(video.added)?.time
                }.getOrNull() ?: 0L
            }
        )
    }

    // ==============================
    // 6. VIDEO LIST EXTRACTION
    // ==============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Method 1: Look for video sources in script tags
        val scriptContent = document.select("script").html()
        val videoSourcesRegex = Regex("""video_sources\s*:\s*(\{.*?\})""")
        videoSourcesRegex.find(scriptContent)?.groups?.get(1)?.value?.let { jsonStr ->
            try {
                val sourcesMap = json.parseAs<Map<String, String>>(jsonStr)
                sourcesMap.forEach { (quality, url) ->
                    val cleanQuality = when (quality) {
                        "2160" -> "4K"
                        "1080" -> "1080p"
                        "720" -> "720p"
                        "480" -> "480p"
                        "240" -> "240p"
                        else -> "${quality}p"
                    }
                    if (url.isNotBlank()) {
                        videoList.add(Video(url, "Direct - $cleanQuality", url))
                    }
                }
            } catch (e: Exception) {
                Log.e("Eporner", "Failed to parse video sources", e)
            }
        }

        // Method 2: Look for MP4 URLs in the page
        if (videoList.isEmpty()) {
            val mp4Regex = Regex("""https?://[^"\s]+\.mp4""")
            mp4Regex.findAll(document.html()).forEach { match ->
                val url = match.value
                if (url.contains("eporner.com") || url.contains("static-ca-cdn")) {
                    val quality = when {
                        url.contains("2160") -> "4K"
                        url.contains("1080") -> "1080p"
                        url.contains("720") -> "720p"
                        url.contains("480") -> "480p"
                        else -> "Unknown"
                    }
                    videoList.add(Video(url, "MP4 - $quality", url))
                }
            }
        }

        return videoList.distinctBy { it.url }
    }

    // ==============================
    // 7. HELPER: Parse API Response
    // ==============================
    private fun parseVideoSearchResponse(response: Response): AnimesPage {
        val apiResponse = response.parseAs<ApiVideoSearchResponse>()
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                thumbnail_url = video.defaultThumb.src
                setUrlWithoutDomain(video.url)
            }
        }
        val hasNextPage = apiResponse.page < apiResponse.totalPages
        return AnimesPage(animeList, hasNextPage)
    }

    // ==============================
    // 8. FILTERS
    // ==============================
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            CategoryFilter(),
            SortFilter(),
            GayFilter()
        )
    }

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "<Select>",
            "anal", "blowjob", "hardcore", "teen", "milf", "big-tits",
            "creampie", "amateur", "homemade", "asian", "lesbian"
        )
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort By",
        arrayOf(
            "<Select>",
            "latest", "most-popular", "top-weekly", "top-monthly",
            "top-rated", "longest", "shortest"
        )
    )

    private class GayFilter : AnimeFilter.Select<Int>(
        "Gay Content",
        arrayOf("Exclude (0)", "Include (1)", "Only (2)")
    ) {
        // Custom state handling for Int values
        override var state: Int
            get() = super.state
            set(value) {
                super.state = value.coerceIn(0, 2)
            }
    }

    // ==============================
    // 9. PREFERENCES
    // ==============================
    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("240p", "480p", "720p", "1080p", "4K")
            entryValues = arrayOf("240p", "480p", "720p", "1080p", "4K")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) 
            ?: PREF_QUALITY_DEFAULT
        
        return sortedWith(
            compareByDescending<Video> { video ->
                when {
                    video.quality.contains(preferredQuality, true) -> 3
                    video.quality.contains("4k", true) -> 2
                    video.quality.contains("1080", true) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                Regex("""(\d+)p""").find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        )
    }
}

// ==============================
// API DATA MODELS
// ==============================

@Serializable
private data class ApiVideoSearchResponse(
    @SerialName("videos") val videos: List<ApiVideo>,
    @SerialName("page") val page: Int,
    @SerialName("total_pages") val totalPages: Int,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("time_ms") val timeMs: Int
)

@Serializable
private data class ApiVideo(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("keywords") val keywords: String,
    @SerialName("views") val views: Long,
    @SerialName("rate") val rate: String,
    @SerialName("url") val url: String,
    @SerialName("added") val added: String,
    @SerialName("length_sec") val lengthSec: Int,
    @SerialName("length_min") val lengthMin: String,
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail
)

@Serializable
private data class ApiThumbnail(
    @SerialName("size") val size: String,
    @SerialName("width") val width: Int,
    @SerialName("height") val height: Int,
    @SerialName("src") val src: String
)
