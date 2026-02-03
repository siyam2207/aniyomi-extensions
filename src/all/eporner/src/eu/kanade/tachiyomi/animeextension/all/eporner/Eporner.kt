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
            val animeList = apiResponse.videos.map { it.toSAnime(tag) }
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
        // Use the embed URL directly for video extraction
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        Log.d(tag, "Using embed URL: $url")
        // Store the anime in the request tag
        return GET(url, headers).newBuilder().tag(SAnime::class.java to anime).build()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        // Get the existing anime from the request tag
        val tagPair = response.request.tag() as? Pair<*, *>
        val anime = if (tagPair?.first == SAnime::class.java) {
            tagPair.second as? SAnime ?: SAnime.create()
        } else {
            SAnime.create()
        }
        return try {
            val body = response.body.string()
            Log.d(tag, "Details response length: ${body.length}")

            // Try to parse as API response first
            if (body.startsWith("{") && body.contains("\"id\"")) {
                try {
                    val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(body)
                    Log.d(tag, "Successfully parsed API response for ID: ${videoDetail.id}")
                    return updateAnimeFromApi(anime, videoDetail, tag)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to parse API response, using embed page: ${e.message}")
                }
            }

            // Use embed page parsing for details
            updateAnimeFromEmbedPage(response, anime)
        } catch (e: Exception) {
            Log.e(tag, "Details parse error", e)
            ensureAnimeBasics(anime)
        }
    }

    private fun updateAnimeFromApi(anime: SAnime, videoDetail: ApiVideoDetailResponse, tag: String): SAnime {
        return anime.apply {
            // Only update title if it's empty or "Unknown Title"
            if (title.isBlank() || title == "Unknown Title") {
                this.title = videoDetail.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            }
            // IMPORTANT: Use embed URL for video extraction
            this.url = "https://www.eporner.com/embed/${videoDetail.id}/"

            // Update thumbnail if missing
            if (thumbnail_url.isNullOrBlank()) {
                this.thumbnail_url = videoDetail.defaultThumb.src.takeIf { it.isNotBlank() }
            }

            // Update genre if missing
            if (genre.isNullOrBlank()) {
                this.genre = videoDetail.keywords.takeIf { it.isNotBlank() }
            }

            // ALWAYS assign description
            val lengthMin = videoDetail.lengthSec / 60
            val lengthSec = videoDetail.lengthSec % 60
            this.description = "Views: ${videoDetail.views} | Length: ${lengthMin}m ${lengthSec}s"

            this.status = SAnime.COMPLETED

            Log.d(tag, "API updated: title='$title', embedURL='$url'")
        }
    }

    private fun updateAnimeFromEmbedPage(response: Response, anime: SAnime): SAnime {
        return try {
            val document = response.asJsoup()
            anime.apply {
                // Only update title if it's empty or "Unknown Title"
                if (title.isBlank() || title == "Unknown Title") {
                    title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }
                        ?: "Unknown Title"
                }

                // Update thumbnail if missing
                if (thumbnail_url.isNullOrBlank()) {
                    thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img.thumb")?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                }

                // Update description if missing
                if (description.isNullOrBlank()) {
                    description = "Eporner Video"
                }

                // Update genre if missing
                if (genre.isNullOrBlank()) {
                    val tags = document.select("a.tag").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }
                    if (tags.isNotEmpty()) {
                        genre = tags.joinToString(", ")
                    }
                }

                status = SAnime.COMPLETED

                Log.d(tag, "Embed page updated: title='$title', thumbnail=${thumbnail_url != null}, genre=${genre != null}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Embed page details parse error", e)
            ensureAnimeBasics(anime)
        }
    }

    private fun ensureAnimeBasics(anime: SAnime): SAnime {
        return anime.apply {
            if (title.isBlank()) title = "Unknown Title"
            if (description.isNullOrBlank()) description = "Eporner Video"
            status = SAnime.COMPLETED
        }
    }

    // ==================== Video Headers for 403 Fix ====================
    private fun videoHeaders(): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", baseUrl)
            .add("Origin", baseUrl)
            .build()
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

    // ==================== Video Extraction (Fixed and Simplified) ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val html = response.body.string()
            val videos = mutableListOf<Video>()

            Log.d(tag, "Parsing embed page for video sources (HTML length: ${html.length})")

            // METHOD 1: Extract from Eporner videoSources array (most reliable)
            val videoSourcesPattern = Regex("""['"]?file['"]?\s*:\s*['"]([^'"]+)['"][^}]*?['"]?label['"]?\s*:\s*['"]([^'"]+)['"]""")
            videoSourcesPattern.findAll(html).forEach { match ->
                val url = match.groupValues[1]
                val label = match.groupValues[2]
                if (url.isNotBlank() && url.contains("http")) {
                    videos.add(Video(url, "Eporner - $label", url, videoHeaders()))
                    Log.d(tag, "Found video source: $label - URL: ${url.take(50)}...")
                }
            }

            // METHOD 2: Alternative pattern for video sources
            if (videos.isEmpty()) {
                val altPattern = Regex("""\{[^{}]*?file[^{}]*?label[^{}]*?\}""")
                altPattern.findAll(html).forEach { match ->
                    val objText = match.value
                    val urlMatch = Regex("""['"]?file['"]?\s*:\s*['"]([^'"]+)['"]""").find(objText)
                    val labelMatch = Regex("""['"]?label['"]?\s*:\s*['"]([^'"]+)['"]""").find(objText)

                    if (urlMatch != null && labelMatch != null) {
                        val url = urlMatch.groupValues[1]
                        val label = labelMatch.groupValues[1]
                        if (url.isNotBlank() && url.contains("http")) {
                            videos.add(Video(url, "Eporner - $label", url, videoHeaders()))
                            Log.d(tag, "Found alt video source: $label")
                        }
                    }
                }
            }

            // METHOD 3: HLS streams (.m3u8) - Simplified approach
            val hlsPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            hlsPattern.findAll(html).forEach { match ->
                val url = match.value
                if (url.isNotBlank() && url.contains(".m3u8")) {
                    try {
                        // Try to extract HLS streams with a simplified approach
                        Log.d(tag, "Found HLS stream: $url")
                        // Create a simple video entry for HLS
                        videos.add(Video(url, "HLS Stream", url, videoHeaders()))
                    } catch (e: Exception) {
                        Log.e(tag, "HLS extraction failed: ${e.message}")
                    }
                }
            }

            // METHOD 4: Direct MP4 links as fallback
            if (videos.isEmpty()) {
                val mp4Patterns = listOf(
                    Regex("""src\s*:\s*['"](https?://[^'"]+\.mp4[^'"]*)['"]"""),
                    Regex("""(https?://[^"'\s]+\.mp4)"""),
                    Regex("""['"]?videoUrl['"]?\s*:\s*['"]([^'"]+\.mp4)['"]"""),
                )

                for (pattern in mp4Patterns) {
                    pattern.findAll(html).forEach { match ->
                        val url = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value.takeIf { it.isNotBlank() }
                        if (url != null && url.startsWith("http") && url.contains(".mp4") && !videos.any { it.videoUrl == url }) {
                            val quality = url.extractQualityFromUrl()
                            videos.add(Video(url, "MP4 - $quality", url, videoHeaders()))
                            Log.d(tag, "Found MP4: $quality")
                        }
                    }
                    if (videos.isNotEmpty()) break
                }
            }

            // METHOD 5: Look for video URLs in specific script blocks
            if (videos.isEmpty()) {
                val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                scriptPattern.findAll(html).forEach { scriptMatch ->
                    val scriptContent = scriptMatch.groupValues[1]

                    // Look for URLs with quality indicators
                    val urlPattern = Regex("""['"](https?://[^'"]+(?:1080|720|480|360|240)[^'"]*\.mp4)['"]""")
                    urlPattern.findAll(scriptContent).forEach { urlMatch ->
                        val url = urlMatch.groupValues[1]
                        if (url.isNotBlank() && !videos.any { it.videoUrl == url }) {
                            val quality = url.extractQualityFromUrl()
                            videos.add(Video(url, "Script - $quality", url, videoHeaders()))
                            Log.d(tag, "Found script MP4: $quality")
                        }
                    }
                }
            }

            Log.d(tag, "Total videos found: ${videos.size}")

            if (videos.isEmpty()) {
                Log.w(tag, "No videos found in HTML. First 500 chars of HTML: ${html.take(500)}")
            }

            // Sort by quality (highest first)
            videos.sortedByDescending { video ->
                val quality = video.quality
                when {
                    quality.contains("1080") -> 1080
                    quality.contains("720") -> 720
                    quality.contains("480") -> 480
                    quality.contains("360") -> 360
                    quality.contains("240") -> 240
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error: ${e.message}", e)
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
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(tag: String): SAnime = SAnime.create().apply {
            // ALWAYS assign title - never leave lateinit var uninitialized
            this.title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown Title"

            // IMPORTANT: Use embed URL for video extraction
            this.url = this@ApiVideo.embed

            // Safe thumbnail (can be null)
            this.thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }

            // Genre can be null
            this.genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }

            this.status = SAnime.COMPLETED

            Log.d(tag, "Search result: title='$title', embedURL='$url'")
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )
}
