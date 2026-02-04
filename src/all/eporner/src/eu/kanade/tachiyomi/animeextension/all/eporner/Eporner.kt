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
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
                else -> {
                    // Do nothing for other filter types
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

            // Better API detection using Content-Type - use safe call
            val contentType = response.header("Content-Type") ?: ""
            val isLikelyJson = contentType.contains("application/json") ||
                (body.startsWith("{") && body.contains("\"id\""))

            if (isLikelyJson) {
                try {
                    val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(body)
                    return updateAnimeFromApi(anime, videoDetail)
                } catch (e: Exception) {
                    // Fall back to embed page parsing
                }
            }

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

    private fun updateAnimeFromApi(anime: SAnime, videoDetail: ApiVideoDetailResponse): SAnime {
        return anime.apply {
            val newTitle = videoDetail.title.takeIf { it.isNotBlank() }
            if (newTitle != null) {
                this.title = newTitle
            }

            this.url = "https://www.eporner.com/embed/${videoDetail.id}/"

            if (thumbnail_url.isNullOrBlank()) {
                this.thumbnail_url = videoDetail.defaultThumb.src.takeIf { it.isNotBlank() }
            }

            if (genre.isNullOrBlank()) {
                this.genre = videoDetail.keywords.takeIf { it.isNotBlank() }
            }

            val lengthMin = videoDetail.lengthSec / 60
            val lengthSec = videoDetail.lengthSec % 60
            this.description = "Views: ${videoDetail.views} | Length: ${lengthMin}m ${lengthSec}s"

            this.status = SAnime.COMPLETED
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
            }
        )
    }

    // ==================== SIMPLIFIED VIDEO EXTRACTION ====================
    override fun videoListParse(response: Response): List<Video> {
        return try {
            val html = response.body.string()
            val embedUrl = response.request.url.toString()

            // Extract video ID from embed URL
            val videoId = extractVideoId(embedUrl) ?: return emptyList()

            // Extract hash from HTML for XHR request
            val hash = extractHashFromHtml(html) ?: return emptyList()

            Log.d(tag, "Video ID: $videoId, Hash: $hash")

            // Make XHR API request to get video sources
            val xhrUrl = "$baseUrl/xhr/video/$videoId?hash=$hash&domain=www.eporner.com&pixelRatio=2.625&playerWidth=0&playerHeight=0&fallback=false&embed=true&supportedFormats=hls,dash,mp4&_=${System.currentTimeMillis()}"

            Log.d(tag, "XHR URL: $xhrUrl")

            val xhrRequest = GET(
                xhrUrl,
                headersBuilder()
                    .add("Referer", embedUrl)
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()
            )

            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body.string()

            Log.d(tag, "XHR Response received: ${xhrBody.length} chars")

            // Parse the JSON response
            val videoSources = parseVideoSources(xhrBody, embedUrl)

            if (videoSources.isNotEmpty()) {
                return videoSources
            }

            // Fallback: Try to find master.m3u8 in the XHR response
            val masterUrl = findMasterUrlInXhrResponse(xhrBody)
            if (masterUrl != null) {
                Log.d(tag, "Found master URL in XHR: $masterUrl")
                return extractVideosFromMasterPlaylist(masterUrl, embedUrl)
            }

            // Final fallback: Look for direct MP4 URLs
            extractDirectMp4Urls(xhrBody, embedUrl)
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error", e)
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        // Extract video ID from embed URL like https://www.eporner.com/embed/U2yTNNin3Wh/
        val pattern = Regex("""embed/([^/]+)/?$""")
        return pattern.find(url)?.groupValues?.get(1)
    }

    private fun extractHashFromHtml(html: String): String? {
        // Look for hash in script tags - it's a 24-character alphanumeric string
        val hashPattern = Regex("""hash=([a-zA-Z0-9]{24})""")
        val match = hashPattern.find(html)

        if (match != null) {
            return match.groupValues[1]
        }

        // Alternative pattern: look for hash in XHR requests in script
        val xhrPattern = Regex("""/xhr/video/[^?]+\?hash=([a-zA-Z0-9]{24})""")
        val xhrMatch = xhrPattern.find(html)

        return xhrMatch?.groupValues?.get(1)
    }

    private fun parseVideoSources(jsonBody: String, embedUrl: String): List<Video> {
        return try {
            val videos = mutableListOf<Video>()

            // Try to parse the JSON response as VideoSourcesResponse
            try {
                val videoSourcesResponse = json.decodeFromString<VideoSourcesResponse>(jsonBody)
                // Parse sources array
                if (videoSourcesResponse.sources != null) {
                    for (source in videoSourcesResponse.sources) {
                        if (source.src != null && source.type != null &&
                            (source.type.contains("mp4") || source.type.contains("video"))
                        ) {
                            val quality = source.label ?: extractQualityFromUrl(source.src)
                            videos.add(Video(source.src, quality, source.src, videoHeaders(embedUrl)))
                            Log.d(tag, "Found video source: $quality")
                        }
                    }
                }

                // Check for HLS, DASH, or MP4 URLs
                if (videoSourcesResponse.hls != null) {
                    Log.d(tag, "Found HLS URL: ${videoSourcesResponse.hls}")
                    return extractVideosFromMasterPlaylist(videoSourcesResponse.hls, embedUrl)
                }

                if (videoSourcesResponse.dash != null) {
                    Log.d(tag, "Found DASH URL: ${videoSourcesResponse.dash}")
                    videos.add(Video(videoSourcesResponse.dash, "DASH", videoSourcesResponse.dash, videoHeaders(embedUrl)))
                }

                if (videoSourcesResponse.mp4 != null) {
                    Log.d(tag, "Found MP4 URL: ${videoSourcesResponse.mp4}")
                    val quality = extractQualityFromUrl(videoSourcesResponse.mp4)
                    videos.add(Video(videoSourcesResponse.mp4, quality, videoSourcesResponse.mp4, videoHeaders(embedUrl)))
                }

                return videos
            } catch (e: Exception) {
                Log.d(tag, "Not VideoSourcesResponse format")
            }

            // Try alternative format
            try {
                // Look for MP4 URLs directly in JSON
                val mp4Pattern = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                val mp4Matches = mp4Pattern.findAll(jsonBody)

                mp4Matches.forEach { match ->
                    val url = match.groupValues[1]
                    if (url.contains("eporner.com") && !url.contains("thumb") && !url.contains("preview")) {
                        val quality = extractQualityFromUrl(url)
                        videos.add(Video(url, quality, url, videoHeaders(embedUrl)))
                        Log.d(tag, "Found MP4 in JSON: $quality")
                    }
                }

                // Look for HLS URLs
                val hlsPattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val hlsMatches = hlsPattern.findAll(jsonBody)

                hlsMatches.forEach { match ->
                    val url = match.groupValues[1]
                    if (url.contains("eporner.com")) {
                        val hlsVideos = extractVideosFromMasterPlaylist(url, embedUrl)
                        if (hlsVideos.isNotEmpty()) {
                            return hlsVideos
                        }
                    }
                }

                return videos
            } catch (e: Exception) {
                Log.d(tag, "Failed to parse alternative format")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing video sources JSON", e)
            emptyList()
        }
    }

    private fun extractQualityFromUrl(url: String): String {
        // Extract quality from URL like .../720p/... or ..._720p.mp4
        val patterns = listOf(
            Regex("""/(\d+)p/"""),
            Regex("""_(\d+)p\.mp4"""),
            Regex("""\.(\d+)\.mp4"""),
            Regex("""quality=(\d+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return "${match.groupValues[1]}p"
            }
        }

        // Default quality
        return if (url.contains("1080")) {
            "1080p"
        } else if (url.contains("720")) {
            "720p"
        } else if (url.contains("480")) {
            "480p"
        } else if (url.contains("360")) {
            "360p"
        } else if (url.contains("240")) {
            "240p"
        } else {
            "Unknown"
        }
    }

    private fun findMasterUrlInXhrResponse(jsonBody: String): String? {
        // Look for master.m3u8 URL in the JSON response
        val patterns = listOf(
            Regex("""(https?://[^"'\s]+master\.m3u8[^"'\s]*)"""),
            Regex("""masterUrl["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""hls["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""url["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        )

        for (pattern in patterns) {
            val match = pattern.find(jsonBody)
            if (match != null) {
                val url = match.groups[1]?.value ?: match.value
                // Clean up the URL
                return url.replace("\\/", "/").replace("\"", "").replace("'", "")
            }
        }

        return null
    }

    private fun extractVideosFromMasterPlaylist(masterUrl: String, embedUrl: String): List<Video> {
        return try {
            val videos = mutableListOf<Video>()

            // Fetch the master playlist
            val masterRequest = GET(masterUrl, videoHeaders(embedUrl))
            val masterResponse = client.newCall(masterRequest).execute()
            val masterContent = masterResponse.body.string()

            Log.d(tag, "Master playlist content length: ${masterContent.length}")

            // Parse master playlist for variant streams
            val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=(\d+)x(\d+)[^\n]*\n([^\n]+)""")
            val matches = variantPattern.findAll(masterContent)

            matches.forEach { match ->
                val width = match.groupValues[1]
                val height = match.groupValues[2]
                val streamPath = match.groupValues[3].trim()

                val streamUrl = if (streamPath.startsWith("http")) {
                    streamPath
                } else {
                    val base = masterUrl.substringBeforeLast("/")
                    "$base/$streamPath"
                }

                videos.add(Video(streamUrl, "${height}p", streamUrl, videoHeaders(embedUrl)))
                Log.d(tag, "Added HLS stream: ${height}p")
            }

            // If no variants found, try alternative pattern
            if (videos.isEmpty()) {
                val altPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*BANDWIDTH=(\d+)[^\n]*\n([^\n]+)""")
                val altMatches = altPattern.findAll(masterContent)

                altMatches.forEachIndexed { index, match ->
                    val bandwidth = match.groupValues[1]
                    val streamPath = match.groupValues[2].trim()

                    val streamUrl = if (streamPath.startsWith("http")) {
                        streamPath
                    } else {
                        val base = masterUrl.substringBeforeLast("/")
                        "$base/$streamPath"
                    }

                    // Convert bandwidth to approximate quality
                    val quality = when (bandwidth.toIntOrNull() ?: 0) {
                        in 4000000..Int.MAX_VALUE -> "1080p"
                        in 2000000..3999999 -> "720p"
                        in 1000000..1999999 -> "480p"
                        in 500000..999999 -> "360p"
                        else -> "240p"
                    }

                    videos.add(Video(streamUrl, quality, streamUrl, videoHeaders(embedUrl)))
                }
            }

            // If still no videos, use the master URL itself
            if (videos.isEmpty()) {
                videos.add(Video(masterUrl, "HLS", masterUrl, videoHeaders(embedUrl)))
            }

            videos
        } catch (e: Exception) {
            Log.e(tag, "Error extracting from master playlist", e)
            emptyList()
        }
    }

    private fun extractDirectMp4Urls(html: String, embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Look for direct MP4 URLs
        val mp4Pattern = Regex(""""(https?://[^"]+\.mp4[^"]*)""")
        val mp4Matches = mp4Pattern.findAll(html)

        mp4Matches.forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains("eporner") && !videoUrl.contains("thumb") && !videoUrl.contains("preview")) {
                val quality = extractQualityFromUrl(videoUrl)
                videos.add(Video(videoUrl, quality, videoUrl, videoHeaders(embedUrl)))
                Log.d(tag, "Found direct MP4: $quality")
            }
        }

        return videos
    }

    // ==================== SETTINGS ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Initialize preferences with the context from the screen
        preferences = android.preference.PreferenceManager.getDefaultSharedPreferences(screen.context)

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
            }
        )
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        DurationFilter(),
        QualityFilter()
    )

    // ==================== FILTER CLASSES ====================
    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray()
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
            Pair("Teen", "teen")
        )
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            Pair("Any", "0"),
            Pair("10+ min", "10"),
            Pair("20+ min", "20"),
            Pair("30+ min", "30")
        )
    )

    private class QualityFilter : UriPartFilter(
        "Quality",
        arrayOf(
            Pair("Any", "0"),
            Pair("HD 1080", "1080"),
            Pair("HD 720", "720"),
            Pair("HD 480", "480")
        )
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p")

        private const val PREF_SORT_KEY = "default_sort"
        private const val PREF_SORT_DEFAULT = "top-weekly"
        private val SORT_LIST = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated")
    }

    // ==================== API DATA CLASSES ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int
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
        @SerialName("thumbs") val thumbs: List<ApiThumbnail>
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail
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
        @SerialName("height") val height: Int
    )

    // ==================== VIDEO SOURCES DATA CLASSES ====================
    @Serializable
    private data class VideoSourcesResponse(
        @SerialName("sources") val sources: List<VideoSource>? = null,
        @SerialName("hls") val hls: String? = null,
        @SerialName("dash") val dash: String? = null,
        @SerialName("mp4") val mp4: String? = null
    )

    @Serializable
    private data class VideoSource(
        @SerialName("src") val src: String? = null,
        @SerialName("type") val type: String? = null,
        @SerialName("label") val label: String? = null
    )
}
