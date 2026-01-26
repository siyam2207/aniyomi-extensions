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
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()
    private val tag = "EpornerExtension"

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
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        // Extract video ID from URL (e.g., "https://www.eporner.com/video-abc123/title")
        val videoId = anime.url.substringAfter("/video-").substringBefore("/")
        val url = "$apiUrl/video/id/?id=$videoId&format=json"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            // Parse the correct API response structure
            val apiResponse = json.decodeFromString<SingleVideoApiResponse>(response.body.string())
            apiResponse.video.toSAnime()
        } catch (e: Exception) {
            Log.e(tag, "Details parse error: ${e.message}", e)
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

    // ==================== Video Extraction (WORKING FIX) ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        // METHOD 1: Extract from script JSON data (Eporner stores video URLs here)
        document.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for video source patterns
            val patterns = listOf(
                Regex("""["']?quality["']?\s*:\s*["']?(\d+)p?["']?\s*,\s*["']?videoUrl["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""["']?src["']?\s*:\s*["']([^"']+\.mp4)["']"""),
                Regex("""https?://[^"']+\.mp4"""),
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val quality = match.groupValues.getOrNull(1) ?: "720"
                    val videoUrl = match.groupValues.getOrNull(2) ?: match.value
                    
                    if (videoUrl.contains(".mp4") && videoUrl.startsWith("http")) {
                        val cleanQuality = if (quality.toIntOrNull() != null) "${quality}p" else "Unknown"
                        videos.add(Video(videoUrl, "Eporner - $cleanQuality", videoUrl))
                    }
                }
            }
        }
        
        // METHOD 2: Extract from video tags
        document.select("video source").forEach { source ->
            val url = source.attr("abs:src")
            val quality = source.attr("label").ifEmpty { "Source" }
            if (url.isNotBlank() && url.contains(".mp4")) {
                videos.add(Video(url, "Direct - $quality", url))
            }
        }
        
        // METHOD 3: Extract HLS streams
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val hlsRegex = Regex("""(https?:[^"' ]+\.m3u8[^"' ]*)""")
            hlsRegex.findAll(scriptText).forEach { match ->
                val url = match.value
                if (url.contains(".m3u8")) {
                    try {
                        val playlistUtils = PlaylistUtils(client, headers)
                        videos.addAll(
                            playlistUtils.extractFromHls(
                                url,
                                response.request.url.toString(),
                                videoNameGen = { quality -> "HLS - $quality" },
                            ),
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "HLS extraction failed: ${e.message}")
                    }
                }
            }
        }
        
        // METHOD 4: Fallback - Check common video URL patterns
        if (videos.isEmpty()) {
            val bodyText = document.body().html()
            val fallbackPattern = Regex("""https?://[^"']+\.mp4[^"']*""")
            fallbackPattern.findAll(bodyText).forEach { match ->
                val url = match.value
                if (url.contains("cdn.eporner.com") || url.contains("eporner.com/video")) {
                    val quality = when {
                        url.contains("1080") -> "1080p"
                        url.contains("720") -> "720p"
                        url.contains("480") -> "480p"
                        url.contains("360") -> "360p"
                        else -> "Unknown"
                    }
                    videos.add(Video(url, "Eporner - $quality", url))
                }
            }
        }
        
        return videos.distinctBy { it.videoUrl }.takeIf { it.isNotEmpty() } ?: emptyList()
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
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p")
    }

    // ==================== API Data Classes ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
    )

    @Serializable
    private data class SingleVideoApiResponse(
        @SerialName("video") val video: ApiVideoDetail,
    )

    @Serializable
    private data class ApiVideoDetail(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("views") val views: Long,
        @SerialName("url") val url: String,
        @SerialName("added") val added: String,
        @SerialName("length_sec") val lengthSec: Int,
        @SerialName("length_min") val lengthMin: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
        @SerialName("thumbs") val thumbs: List<ApiThumbnail>,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.url = this@ApiVideoDetail.url
            this.title = this@ApiVideoDetail.title
            this.thumbnail_url = this@ApiVideoDetail.defaultThumb.src
            this.genre = this@ApiVideoDetail.keywords
            this.description = "Views: $views | Length: $lengthMin"
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
