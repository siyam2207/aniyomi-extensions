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

    // ==================== Headers (CRITICAL FIX) ====================
    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .add("Accept", "application/json, text/plain, */*")  // ✅ API needs application/json
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("Connection", "keep-alive")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("Sec-Fetch-Dest", "empty")  // ✅ API uses 'empty' not 'document'
            .add("Sec-Fetch-Mode", "cors")   // ✅ API uses 'cors' not 'navigate'
            .add("Sec-Fetch-Site", "same-origin")
            .add("Pragma", "no-cache")
            .add("Cache-Control", "no-cache")
    }
    
    // Headers for HTML pages (different from API)
    private fun htmlHeadersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Cache-Control", "max-age=0")
    }

    // ==================== Popular Anime ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json"
        Log.d(tag, "Popular request URL: $url")
        return GET(url, headersBuilder().build())  // ✅ Use API headers
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        Log.d(tag, "=== POPULAR ANIME PARSE START ===")
        Log.d(tag, "Response Code: ${response.code}")
        Log.d(tag, "Response URL: ${response.request.url}")
        
        return try {
            val responseBody = response.body.string()
            Log.d(tag, "Response Body length: ${responseBody.length}")
            Log.d(tag, "Response first 200 chars: ${responseBody.take(200)}")
            
            // Check if response is HTML (403 error page)
            if (responseBody.contains("<html") || responseBody.contains("403") || responseBody.isBlank()) {
                Log.e(tag, "Got HTML or empty response instead of JSON!")
                return AnimesPage(emptyList(), false)
            }
            
            val apiResponse = json.decodeFromString<ApiSearchResponse>(responseBody)
            Log.d(tag, "Parsed ${apiResponse.videos.size} videos, page ${apiResponse.page}/${apiResponse.total_pages}")
            
            if (apiResponse.videos.isEmpty()) {
                Log.w(tag, "API returned empty videos list!")
            } else {
                apiResponse.videos.take(3).forEachIndexed { index, video ->
                    Log.d(tag, "Video $index sample: ${video.title.take(30)}... (ID: ${video.id})")
                }
            }
            
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error: ${e.message}", e)
            AnimesPage(emptyList(), false)
        } finally {
            Log.d(tag, "=== POPULAR ANIME PARSE END ===")
        }
    }

    // ==================== Latest Updates ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json"
        Log.d(tag, "Latest request URL: $url")
        return GET(url, headersBuilder().build())  // ✅ Use API headers
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
        return GET(url, headersBuilder().build())  // ✅ Use API headers
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val videoId = anime.url.substringAfterLast("/").substringBefore("-")
        val url = "$apiUrl/video/id/?id=$videoId&format=json"
        Log.d(tag, "Details request to: $url")
        return GET(url, headersBuilder().build())  // ✅ Use API headers
    }

    override fun animeDetailsParse(response: Response): SAnime {
        Log.d(tag, "Details Response Code: ${response.code}")
        
        if (!response.isSuccessful) {
            Log.e(tag, "Details request failed with code: ${response.code}")
            // Try to get error response body
            try {
                val errorBody = response.body.string()
                Log.e(tag, "Error response: ${errorBody.take(200)}")
            } catch (e: Exception) {
                Log.e(tag, "Could not read error body")
            }
            response.close()
            return SAnime.create().apply {
                title = "Error loading details (HTTP ${response.code})"
                status = SAnime.COMPLETED
            }
        }
        
        return try {
            val responseBody = response.body.string()
            Log.d(tag, "Details response length: ${responseBody.length}")
            
            val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(responseBody)
            videoDetail.toSAnime()
        } catch (e: Exception) {
            Log.w(tag, "API details failed: ${e.message}")
            SAnime.create().apply {
                title = "Error parsing API response"
                status = SAnime.COMPLETED
            }
        }
    }

    private fun htmlAnimeDetailsParse(response: Response): SAnime {
        return try {
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
        } catch (e: Exception) {
            Log.e(tag, "HTML details parse error: ${e.message}")
            SAnime.create().apply {
                title = "Error loading details"
                status = SAnime.COMPLETED
            }
        }
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }

    // ==================== Episodes & Video Requests ====================
    override fun episodeListRequest(anime: SAnime): Request {
        // ✅ Use HTML headers for webpage
        return GET(anime.url, htmlHeadersBuilder().build())
    }

    override fun videoListRequest(episode: SEpisode): Request {
        // ✅ Use HTML headers for webpage
        return GET(episode.url, htmlHeadersBuilder().build())
    }

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
        Log.d(tag, "Video list parse, Response Code: ${response.code}")
        
        if (!response.isSuccessful) {
            Log.e(tag, "Video list request failed with code: ${response.code}")
            return emptyList()
        }
        
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()

            // METHOD 1: Eporner JavaScript pattern (ENHANCED)
            document.select("script").forEach { script ->
                val scriptText = script.html()
                // Enhanced pattern matching
                val patterns = listOf(
                    Regex("""quality["']?\s*:\s*["']?(\d+)["']?\s*,\s*videoUrl["']?\s*:\s*["']([^"']+)["']"""),
                    Regex("""videoUrl["']?\s*:\s*["']([^"']+)["']\s*,\s*quality["']?\s*:\s*["']?(\d+)["']"""),
                    Regex(""""quality"\s*:\s*"(\d+)"\s*,\s*"videoUrl"\s*:\s*"([^"]+)"""),
                )
                patterns.forEach { pattern ->
                    pattern.findAll(scriptText).forEach { match ->
                        val quality = match.groupValues.getOrNull(1) ?: match.groupValues.getOrNull(2) ?: ""
                        val videoUrl = match.groupValues.getOrNull(2) ?: match.groupValues.getOrNull(1) ?: ""
                        if (videoUrl.isNotBlank() && quality.isNotBlank()) {
                            videos.add(Video(videoUrl, "Eporner - ${quality}p", videoUrl))
                            Log.d(tag, "Found video: ${quality}p - ${videoUrl.take(50)}...")
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
                                val playlistUtils = PlaylistUtils(client, htmlHeadersBuilder().build())
                                val hlsVideos = playlistUtils.extractFromHls(
                                    url,
                                    response.request.url.toString(),
                                    videoNameGen = { quality -> "HLS - $quality" },
                                )
                                videos.addAll(hlsVideos)
                            } catch (e: Exception) {
                                Log.e(tag, "HLS extraction failed: ${e.message}")
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
                        val url = match.groupValues.getOrNull(1) ?: match.value
                        if (url.isNotBlank() && url.startsWith("http") && !videos.any { it.videoUrl == url }) {
                            addVideoWithQuality(videos, url)
                        }
                    }
                }
            }

            Log.d(tag, "Total videos found: ${videos.size}")
            videos.distinctBy { it.videoUrl }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error: ${e.message}", e)
            emptyList()
        }
    }

    private fun addVideoWithQuality(videos: MutableList<Video>, url: String) {
        val quality = when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Unknown"
        }
        videos.add(Video(url, "Direct - $quality", url))
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
            this.url = this@ApiVideoDetailResponse.url
            this.title = this@ApiVideoDetailResponse.title
            this.thumbnail_url = this@ApiVideoDetailResponse.defaultThumb.src
            this.genre = this@ApiVideoDetailResponse.keywords
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
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = this@ApiVideo.url
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
