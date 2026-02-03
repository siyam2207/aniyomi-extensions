package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val tag = "EpornerExtension"

    // User agent constant
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // Store preferences instance
    private var preferences: SharedPreferences? = null

    // ==================== Headers ====================
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
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
            val apiResponse = json.decodeFromString<ApiSearchResponse>(body)
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
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        // Use the embed URL directly for video extraction
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
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

        ensureTitleInitialized(anime)

        return try {
            val body = response.body.string()
            updateAnimeFromEmbedPage(body, anime)
        } catch (e: Exception) {
            ensureAnimeBasics(anime)
        }
    }

    private fun ensureTitleInitialized(anime: SAnime) {
        try {
            anime.title
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            anime.title = "Unknown Title"
        }
    }

    private fun updateAnimeFromEmbedPage(html: String, anime: SAnime): SAnime {
        return try {
            val document = Jsoup.parse(html)
            anime.apply {
                val pageTitle = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }

                if (pageTitle != null) {
                    this.title = pageTitle
                }

                if (thumbnail_url.isNullOrBlank()) {
                    thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img.thumb")?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                }

                if (description.isNullOrBlank()) {
                    description = "Eporner Video"
                }

                if (genre.isNullOrBlank()) {
                    val tags = document.select("a.tag").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }
                    if (tags.isNotEmpty()) {
                        genre = tags.joinToString(", ")
                    }
                }

                status = SAnime.COMPLETED
            }
        } catch (e: Exception) {
            ensureAnimeBasics(anime)
        }
    }

    private fun ensureAnimeBasics(anime: SAnime): SAnime {
        return anime.apply {
            ensureTitleInitialized(this)
            if (description.isNullOrBlank()) description = "Eporner Video"
            status = SAnime.COMPLETED
        }
    }

    // ==================== Clean Video Headers ====================
    private fun videoHeaders(embedUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", embedUrl)
            .add("Origin", "https://www.eporner.com")
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Connection", "keep-alive")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun xhrHeaders(referer: String): Headers {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", referer)
            .add("Origin", baseUrl)
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .build()
    }

    // ==================== Required Methods ====================
    override fun videoUrlParse(response: Response): String {
        val videos = videoListParse(response)
        return videos.firstOrNull()?.videoUrl ?: throw Exception("No videos found")
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

    // ==================== XHR Video Extraction ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val html = response.body.string()
            val embedUrl = response.request.url.toString()
            
            // Extract videoId from embed URL
            val videoId = extractVideoId(embedUrl) ?: return emptyList()
            
            // Extract hash token from HTML
            val hash = extractHashToken(html) ?: return emptyList()
            
            // Call the xhr API endpoint
            val videoSources = fetchVideoSources(videoId, hash, embedUrl)
            
            // Parse HLS from API response
            val videos = mutableListOf<Video>()
            
            // Add HLS sources if available
            videoSources.hlsUrl?.let { hlsUrl ->
                videos.addAll(
                    PlaylistUtils(client, videoHeaders(embedUrl))
                        .extractFromHls(hlsUrl, embedUrl) { quality ->
                            "${quality.height}p"
                        }
                )
            }
            
            // Add MP4 fallback sources
            videoSources.mp4Sources?.forEach { (quality, url) ->
                videos.add(Video(url, "MP4 $quality", url, videoHeaders(embedUrl)))
            }
            
            videos.sortedByDescending { 
                it.quality.replace("p", "").toIntOrNull() ?: 0 
            }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error", e)
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        val pattern = """embed/([^/]+)/?""".toRegex()
        return pattern.find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractHashToken(html: String): String? {
        val patterns = listOf(
            """hash["']?\s*[:=]\s*["']([^"']+)["']""",
            """var\s+hash\s*=\s*["']([^"']+)["']""",
        )
        
        patterns.forEach { pattern ->
            val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(html)
            if (match != null) {
                return match.groupValues.getOrNull(1)
            }
        }
        return null
    }

    private fun fetchVideoSources(videoId: String, hash: String, referer: String): VideoSources {
        val xhrUrl = "$baseUrl/xhr/video/$videoId?hash=$hash"
        val request = GET(xhrUrl, xhrHeaders(referer))
        
        return try {
            val response = client.newCall(request).execute()
            val jsonResponse = response.body.string()
            json.decodeFromString<XhrVideoResponse>(jsonResponse).toVideoSources()
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch video sources", e)
            VideoSources(null, null)
        }
    }

    // ==================== Settings ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Initialize preferences with the context from the screen
        preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(screen.context)

        // Create quality preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)

            // Set current value
            val currentValue = preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            setValueIndex(QUALITY_LIST.indexOf(currentValue).coerceAtLeast(0))
            summary = when (currentValue) {
                "best" -> "Best available quality"
                else -> currentValue
            }

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_QUALITY_KEY, selected)?.apply()
                this.summary = when (selected) {
                    "best" -> "Best available quality"
                    else -> selected
                }
                true
            }
        }.also(screen::addPreference)

        // Create sort preference
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default sort order"
            entries = SORT_LIST
            entryValues = SORT_LIST
            setDefaultValue(PREF_SORT_DEFAULT)

            // Set current value
            val currentValue = preferences?.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT) ?: PREF_SORT_DEFAULT
            setValueIndex(SORT_LIST.indexOf(currentValue).coerceAtLeast(0))
            summary = currentValue

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_SORT_KEY, selected)?.apply()
                this.summary = selected
                true
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        // Get quality preference safely
        val qualityPref = try {
            preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        } catch (e: Exception) {
            PREF_QUALITY_DEFAULT
        }

        return sortedWith(
            compareByDescending<Video> {
                when {
                    qualityPref == "best" -> it.quality.replace("p", "").toIntOrNull() ?: 0
                    it.quality.contains(qualityPref, ignoreCase = false) -> 1000
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
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            this.url = this@ApiVideo.embed
            this.thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }
            this.genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }
            this.status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )

    // ==================== XHR API Data Classes ====================
    @Serializable
    private data class XhrVideoResponse(
        @SerialName("hls") val hls: XhrHls?,
        @SerialName("mp4") val mp4: Map<String, String>?,
    )

    @Serializable
    private data class XhrHls(
        @SerialName("src") val src: String,
        @SerialName("height") val height: Int?,
    )

    private data class VideoSources(
        val hlsUrl: String?,
        val mp4Sources: Map<String, String>?,
    )

    private fun XhrVideoResponse.toVideoSources(): VideoSources {
        return VideoSources(
            hlsUrl = hls?.src,
            mp4Sources = mp4?.filterValues { it.isNotBlank() }
        )
    }
