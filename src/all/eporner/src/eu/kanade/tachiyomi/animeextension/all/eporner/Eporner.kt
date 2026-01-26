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

    // FIXED: Add proper headers to avoid 403 Forbidden
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
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
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString<ApiSearchResponse>(response.body.string())
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error: ${e.message}", e)
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        // FIXED: Use the video page URL directly instead of API (which gives 403)
        return GET(anime.url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val document = response.asJsoup()
            SAnime.create().apply {
                url = response.request.url.toString()
                
                // Extract title
                title = document.selectFirst("h1")?.text() ?: 
                       document.selectFirst("title")?.text() ?: "Unknown Title"
                
                // Extract thumbnail
                thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content") ?:
                               document.selectFirst("video")?.attr("poster") ?:
                               document.selectFirst("img.thumb")?.attr("src")
                
                // Extract description/views
                val viewsText = document.selectFirst("div.views")?.text() ?: ""
                val durationText = document.selectFirst("div.length")?.text() ?: ""
                
                description = buildString {
                    if (viewsText.isNotEmpty()) append("Views: $viewsText\n")
                    if (durationText.isNotEmpty()) append("Duration: $durationText\n")
                    
                    // Extract tags/keywords
                    val tags = document.select("a.tag").map { it.text() }
                    if (tags.isNotEmpty()) {
                        append("\nTags: ${tags.joinToString(", ")}")
                    }
                }
                
                // Extract tags as genre
                val tags = document.select("a.tag").map { it.text() }
                genre = tags.joinToString(", ")
                
                status = SAnime.COMPLETED
            }
        } catch (e: Exception) {
            Log.e(tag, "Details parse error: ${e.message}", e)
            SAnime.create().apply {
                title = "Could not load details"
                status = SAnime.COMPLETED
            }
        }
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        val videos = videoListParse(response)
        Log.d(tag, "Found ${videos.size} videos")
        return videos.firstOrNull()?.videoUrl ?: ""
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

    // ==================== Video Extraction (FIXED) ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()
            
            // METHOD 1: Check for direct video URLs in data- attributes
            document.select("[data-video-url], [data-src]").forEach { element ->
                val url = element.attr("data-video-url").ifEmpty { element.attr("data-src") }
                if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                    addVideoWithQuality(videos, url)
                }
            }
            
            // METHOD 2: Check for iframe embeds
            document.select("iframe[src*='embed']").forEach { iframe ->
                val embedUrl = iframe.attr("abs:src")
                if (embedUrl.isNotBlank()) {
                    try {
                        val embedResponse = client.newCall(GET(embedUrl, headers)).execute()
                        if (embedResponse.isSuccessful) {
                            val embedDoc = embedResponse.asJsoup()
                            extractVideosFromScripts(embedDoc, videos, embedUrl)
                        }
                        embedResponse.close()
                    } catch (e: Exception) {
                        Log.e(tag, "Embed extraction failed: ${e.message}")
                    }
                }
            }
            
            // METHOD 3: Extract from scripts (most reliable for Eporner)
            extractVideosFromScripts(document, videos, response.request.url.toString())
            
            // METHOD 4: Fallback to common patterns
            if (videos.isEmpty()) {
                val html = document.html()
                val videoPatterns = listOf(
                    Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']"""),
                    Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']"""),
                    Regex("""(https?://[^"'\s<>]+\.mp4)"""),
                )
                
                videoPatterns.forEach { pattern ->
                    pattern.findAll(html).forEach { match ->
                        val url = match.groupValues.getOrNull(1) ?: match.value
                        if (url.startsWith("http") && !videos.any { it.videoUrl == url }) {
                            addVideoWithQuality(videos, url)
                        }
                    }
                }
            }
            
            // Log found videos for debugging
            Log.d(tag, "Total videos found: ${videos.size}")
            videos.forEach { Log.d(tag, "Video: ${it.quality} - ${it.videoUrl.take(50)}...") }
            
            videos.distinctBy { it.videoUrl }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun extractVideosFromScripts(document: org.jsoup.nodes.Document, videos: MutableList<Video>, referer: String) {
        document.select("script:not([src])").forEach { script ->
            val scriptText = script.html()
            
            // Look for Eporner-specific video patterns
            val patterns = listOf(
                // Common Eporner pattern: {quality: "720", videoUrl: "http://..."}
                Regex("""quality["']?\s*:\s*["']?(\d+)["']?\s*,\s*videoUrl["']?\s*:\s*["']([^"']+)["']"""),
                // Direct mp4 URLs in variables
                Regex("""["']?(?:src|file|url)["']?\s*:\s*["'](https?://[^"']+\.mp4)["']"""),
                // HLS streams
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                // Base64 encoded URLs (sometimes used)
                Regex("""["']?(?:video|file)["']?\s*:\s*["'](https?://[^"']+)["']"""),
            )
            
            patterns.forEachIndexed { index, pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    when (index) {
                        0 -> { // quality/videoUrl pattern
                            val quality = match.groupValues[1]
                            val videoUrl = match.groupValues[2]
                            if (videoUrl.isNotBlank()) {
                                videos.add(Video(videoUrl, "Eporner - ${quality}p", videoUrl))
                            }
                        }
                        2 -> { // HLS pattern
                            val url = match.value
                            if (url.contains(".m3u8")) {
                                try {
                                    val playlistUtils = PlaylistUtils(client, headers)
                                    val hlsVideos = playlistUtils.extractFromHls(
                                        url,
                                        referer,
                                        videoNameGen = { quality -> "HLS - $quality" },
                                    )
                                    videos.addAll(hlsVideos)
                                } catch (e: Exception) {
                                    Log.e(tag, "HLS extraction failed: ${e.message}")
                                }
                            }
                        }
                        else -> { // Direct URL patterns
                            val url = match.groupValues.getOrNull(1) ?: match.value
                            if (url.isNotBlank() && (url.contains(".mp4") || url.contains("cdn.eporner"))) {
                                addVideoWithQuality(videos, url)
                            }
                        }
                    }
                }
            }
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
        videos.add(Video(url, "Eporner - $quality", url))
    }

    // ==================== Settings ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
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

    override fun getFilterList() = AnimeFilterList()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p", "240p")
    }

    // ==================== API Data Classes ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
    )

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
