package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Eporner : AnimeHttpSource(), ConfigurableAnimeSource {

    // --- Core Properties ---
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiBaseUrl = "$baseUrl/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // --- Headers & Client ---
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Accept", "application/json, text/html, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Referer", "$baseUrl/")
    }

    // ==============================
    // 1. POPULAR ANIME (Most Viewed)
    // ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", POPULAR_PER_PAGE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "most-popular") // All-time most popular[citation:1]
            .addQueryParameter("thumbsize", PREF_THUMB_SIZE_MEDIUM)
            .addQueryParameter("format", "json")
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 2. LATEST UPDATES (Newest Videos)
    // ==============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", LATEST_PER_PAGE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "latest") // Newest first[citation:1]
            .addQueryParameter("thumbsize", PREF_THUMB_SIZE_MEDIUM)
            .addQueryParameter("format", "json")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 3. SEARCH & FILTERS
    // ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", SEARCH_PER_PAGE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("thumbsize", PREF_THUMB_SIZE_MEDIUM)
            .addQueryParameter("format", "json")

        // Apply filters from the filter list
        var appliedQuery = query
        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state != 0) {
                        appliedQuery = if (appliedQuery.isBlank()) filter.values[filter.state] else "$appliedQuery ${filter.values[filter.state]}"
                    }
                }
                is PornstarFilter -> {
                    if (filter.state != 0) {
                        appliedQuery = if (appliedQuery.isBlank()) filter.values[filter.state] else "$appliedQuery ${filter.values[filter.state]}"
                    }
                }
                is QualityFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("lq", if (filter.values[filter.state] == "hd") "0" else "1")
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("order", filter.values[filter.state])
                    }
                }
                is GayFilter -> {
                    urlBuilder.addQueryParameter("gay", filter.state.toString())
                }
                else -> {}
            }
        }

        // Use query or default to "all" to fetch something[citation:1]
        urlBuilder.addQueryParameter("query", appliedQuery.ifBlank { "all" })
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseVideoSearchResponse(response)
    }

    // ==============================
    // 4. ANIME (VIDEO) DETAILS
    // ==============================
    override fun animeDetailsParse(response: Response): SAnime {
        // Note: The search API returns full details. For a dedicated details call,
        // we would use /api/v2/video/id/[citation:1]. Here we parse the search result item.
        val video = response.parseAs<ApiVideoSearchResponse>().videos.firstOrNull()
            ?: throw Exception("Failed to parse video details")

        return SAnime.create().apply {
            title = video.title
            // Use the first thumbnail from the array as a preview/gallery[citation:1]
            thumbnail_url = video.thumbs.firstOrNull()?.src ?: video.default_thumb.src
            // Keywords contain tags/categories
            genre = video.keywords.split(", ").joinToString()
            description = buildString {
                append("Rating: ${video.rate}\n")
                append("Views: ${video.views}\n")
                append("Duration: ${video.length_min}\n")
                append("Added: ${video.added}\n")
                append("\nTags:\n${video.keywords}")
            }
            status = SAnime.COMPLETED // All videos are completed
            author = "" // Could be parsed from keywords or page if needed
            // Set URL to the video page for episode parsing
            setUrlWithoutDomain(video.url)
        }
    }

    // ==============================
    // 5. EPISODE LIST (Single Episode per Video)
    // ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Each video is a single "episode"
        val videoId = response.request.url.toString().substringAfter("/hd-porn/").substringBefore("/")
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                // URL for loading videos is the embed page, which contains player sources[citation:4]
                setUrlWithoutDomain("$baseUrl/embed/$videoId")
                date_upload = runCatching {
                    dateFormat.parse(response.parseAs<ApiVideoSearchResponse>().videos.firstOrNull()?.added)?.time
                }.getOrNull() ?: 0L
            }
        )
    }

    // ==============================
    // 6. VIDEO LIST & URL EXTRACTION (CRITICAL)
    // ==============================
    override fun videoListParse(response: Response): List<Video> {
        // The embed page contains the video player with quality options[citation:4]
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Method 1: Look for JavaScript array 'video_sources' (common pattern)
        val scriptContent = document.select("script").html()
        val videoSourcesRegex = Regex("""video_sources\s*=\s*(\{.*?\})""")
        videoSourcesRegex.find(scriptContent)?.groups?.get(1)?.value?.let { jsonStr ->
            runCatching {
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
                    videoList.add(Video(url, "Direct - $cleanQuality", url))
                }
            }
        }

        // Method 2: Look for <source> tags inside <video> elements
        if (videoList.isEmpty()) {
            document.select("video source").forEach { source ->
                val url = source.attr("src")
                val quality = source.attr("quality").ifBlank { source.attr("data-quality") }
                if (url.isNotBlank() && url.contains(".mp4")) {
                    videoList.add(Video(url, "HTML5 - ${quality.ifBlank { "Unknown" }}", url))
                }
            }
        }

        // Method 3: Fallback to API for video ID if needed (less direct)
        if (videoList.isEmpty()) {
            val videoId = response.request.url.toString().substringAfterLast("/").substringBefore("?")
            runCatching {
                val apiUrl = "$apiBaseUrl/video/id/".toHttpUrl().newBuilder()
                    .addQueryParameter("id", videoId)
                    .addQueryParameter("format", "json")
                    .build()
                val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
                    .parseAs<ApiVideoDetailResponse>()
                // The detail response may contain a default quality URL or similar hints
                apiResponse.default_thumb?.src?.let {
                    videoList.add(Video(it, "API Fallback", it))
                }
            }
        }

        return videoList.distinctBy { it.url }
    }

    // ==============================
    // 7. HELPER: Parse API Search Response
    // ==============================
    private fun parseVideoSearchResponse(response: Response): AnimesPage {
        val apiResponse = response.parseAs<ApiVideoSearchResponse>()
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                // Use the first thumb from the array for a preview[citation:1]
                thumbnail_url = video.thumbs.firstOrNull()?.src ?: video.default_thumb.src
                setUrlWithoutDomain(video.url)
                // Preview feature: Store all thumbnails in the 'description' field for the UI to potentially use as a gallery
                description = "THUMBNAIL_GALLERY:" + video.thumbs.joinToString("|") { it.src }
            }
        }
        val hasNextPage = apiResponse.page < apiResponse.total_pages
        return AnimesPage(animeList, hasNextPage)
    }

    // ==============================
    // 8. FILTERS IMPLEMENTATION
    // ==============================
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            // Text search is handled by the main query parameter
            AnimeFilter.Header("NOTE: Category & Pornstar filters modify the search text"),
            CategoryFilter(),
            PornstarFilter(),
            AnimeFilter.Separator(),
            QualityFilter(),
            SortFilter(),
            GayFilter(),
            AnimeFilter.Header("Gay: 0=Exclude, 1=Include, 2=Only[citation:1]")
        )
    }

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "<Select>",
            "anal", "blowjob", "hardcore", "teen", "milf", "big-tits", "creampie",
            "amateur", "homemade", "asian", "latina", "ebony", "bdsm", "threesome",
            "orgy", "interracial", "cuckold", "femdom", "hentai", "lesbian"
        )
    )

    private class PornstarFilter : AnimeFilter.Select<String>(
        "Pornstar",
        arrayOf(
            "<Select>",
            "Mia Malkova", "Riley Reid", "Angela White", "Eva Elfie", "Brandi Love",
            "Lena Paul", "Abella Danger", "Gina Valentina", "Adriana Chechik"
            // NOTE: A full list should be populated by parsing /pornstar-list/
        )
    )

    private class QualityFilter : AnimeFilter.Select<String>(
        "Quality",
        arrayOf("<Select>", "hd", "all")
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort By",
        arrayOf(
            "<Select>",
            "latest", "most-popular", "top-weekly", "top-monthly", "top-rated",
            "longest", "shortest"[citation:1]
        )
    )

    private class GayFilter : AnimeFilter.Select<Int>(
        "Gay Content",
        arrayOf("Exclude (0)", "Include (1)", "Only (2)").mapIndexed { i, _ -> i }.toTypedArray()
    )

    // ==============================
    // 9. PREFERENCES (SETTINGS)
    // ==============================
    private companion object Preferences {
        const val PREF_THUMB_SIZE_KEY = "pref_thumb_size"
        const val PREF_THUMB_SIZE_TITLE = "Thumbnail Size"
        const val PREF_THUMB_SIZE_SMALL = "small"
        const val PREF_THUMB_SIZE_MEDIUM = "medium"
        const val PREF_THUMB_SIZE_BIG = "big"
        const val PREF_THUMB_SIZE_DEFAULT = PREF_THUMB_SIZE_BIG

        const val PREF_DEFAULT_QUALITY_KEY = "pref_default_quality"
        const val PREF_DEFAULT_QUALITY_TITLE = "Preferred Quality"
        const val PREF_DEFAULT_QUALITY_DEFAULT = "1080p"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Thumbnail Size Preference[citation:1]
        ListPreference(screen.context).apply {
            key = PREF_THUMB_SIZE_KEY
            title = PREF_THUMB_SIZE_TITLE
            entries = arrayOf("Small (190x152)", "Medium (427x240)", "Big (640x360)")
            entryValues = arrayOf(PREF_THUMB_SIZE_SMALL, PREF_THUMB_SIZE_MEDIUM, PREF_THUMB_SIZE_BIG)
            setDefaultValue(PREF_THUMB_SIZE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }.also(screen::addPreference)

        // Default Video Quality Preference
        ListPreference(screen.context).apply {
            key = PREF_DEFAULT_QUALITY_KEY
            title = PREF_DEFAULT_QUALITY_TITLE
            entries = arrayOf("240p", "480p", "720p", "1080p", "4K")
            entryValues = arrayOf("240p", "480p", "720p", "1080p", "4K")
            setDefaultValue(PREF_DEFAULT_QUALITY_DEFAULT)
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
        val preferredQuality = preferences.getString(PREF_DEFAULT_QUALITY_KEY, PREF_DEFAULT_QUALITY_DEFAULT) ?: PREF_DEFAULT_QUALITY_DEFAULT
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

    // ==============================
    // 10. CONSTANTS
    // ==============================
    private companion object {
        const val POPULAR_PER_PAGE = 30
        const val LATEST_PER_PAGE = 30
        const val SEARCH_PER_PAGE = 30
    }
}

// ==============================
// API DATA MODELS (Serializable)
// ==============================

@Serializable
private data class ApiVideoSearchResponse(
    val videos: List<ApiVideo>,
    val page: Int,
    val total_pages: Int,
    val total_count: Int,
    val per_page: Int,
    val time_ms: Int
)

@Serializable
private data class ApiVideoDetailResponse(
    val id: String,
    val title: String,
    val keywords: String,
    val views: Long,
    val rate: String,
    val url: String,
    val added: String,
    val length_sec: Int,
    val length_min: String,
    val embed: String,
    val default_thumb: ApiThumbnail,
    val thumbs: List<ApiThumbnail>
)

@Serializable
private data class ApiVideo(
    val id: String,
    val title: String,
    val keywords: String,
    val views: Long,
    val rate: String,
    val url: String,
    val added: String,
    val length_sec: Int,
    val length_min: String,
    val embed: String,
    val default_thumb: ApiThumbnail,
    val thumbs: List<ApiThumbnail>
)

@Serializable
private data class ApiThumbnail(
    val size: String,
    val width: Int,
    val height: Int,
    val src: String
)
