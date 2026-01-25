package eu.kanade.tachiyomi.animeextension.all.eporner

import android.annotation.SuppressLint
import android.util.Base64
import androidx.preference.*
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiBaseUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true
    override val supportsRelatedAnimes = true

    // ==================== CLIENT CONFIGURATION ====================
    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor())
        .addInterceptor(RefererInterceptor())
        .addInterceptor(CacheInterceptor())
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val preferences by getPreferencesLazy()

    // ==================== HEADERS MANAGEMENT ====================
    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("DNT", "1")
        .add("Connection", "keep-alive")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Cache-Control", "max-age=0")

    // ==================== INTERNAL COMPONENTS ====================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private var sessionToken: String? = null
    private var csrfToken: String? = null

    // ==================== MAIN CONTENT ENDPOINTS ====================
    companion object {
        // Preferences
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_SORT = "pref_sort"
        private const val PREF_DATE = "pref_date"
        private const val PREF_DURATION = "pref_duration"
        private const val PREF_CATEGORY = "pref_category"
        private const val PREF_AUTOPLAY = "pref_autoplay"
        private const val PREF_PREVIEW = "pref_preview"
        private const val PREF_SUGGESTIONS = "pref_suggestions"
        private const val PREF_HIDE_PREMIUM = "pref_hide_premium"
        private const val PREF_SAVE_HISTORY = "pref_save_history"
        
        // API Endpoints
        private const val API_VIDEO_LIST = "/api/v2/video/list/"
        private const val API_VIDEO_DETAIL = "/api/v2/video/"
        private const val API_SEARCH = "/api/v2/video/search/"
        private const val API_CATEGORIES = "/api/v2/category/list/"
        private const val API_ACTORS = "/api/v2/actor/list/"
        private const val API_SUGGEST = "/api/v2/search/suggest/"
        private const val API_STATS = "/api/v2/stats/"
        private const val API_RELATED = "/api/v2/video/%s/related/"
        
        // Quality mapping
        val QUALITY_MAP = mapOf(
            "2160" to "4K (2160p)",
            "1440" to "QHD (1440p)",
            "1080" to "Full HD (1080p)",
            "720" to "HD (720p)",
            "480" to "SD (480p)",
            "360" to "Low (360p)",
            "240" to "Mobile (240p)",
            "hls" to "Adaptive HLS",
            "auto" to "Auto (Best Available)"
        )
    }

    // ==================== POPULAR ANIME ====================
    override fun popularAnimeRequest(page: Int): Request {
        val sort = preferences.getString(PREF_SORT, "most-viewed") ?: "most-viewed"
        val date = preferences.getString(PREF_DATE, "") ?: ""
        val quality = preferences.getString(PREF_QUALITY, "") ?: ""
        val duration = preferences.getString(PREF_DURATION, "") ?: ""
        val category = preferences.getString(PREF_CATEGORY, "") ?: ""
        
        val url = HttpUrl.parse("$apiBaseUrl$API_VIDEO_LIST")!!.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("order", sort)
            .apply {
                if (date.isNotEmpty()) addQueryParameter("date", date)
                if (quality.isNotEmpty()) addQueryParameter("quality", quality)
                if (duration.isNotEmpty()) addQueryParameter("duration", duration)
                if (category.isNotEmpty()) addQueryParameter("category", category)
                if (preferences.getBoolean(PREF_HIDE_PREMIUM, false)) {
                    addQueryParameter("exclude_premium", "1")
                }
            }
            .build()
        
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString<VideoListResponse>(response.body.string())
            val animes = apiResponse.videos.map { it.toSAnime() }
            AnimesPage(animes, apiResponse.has_next)
        } catch (e: Exception) {
            // Fallback to HTML parsing
            parseHtmlVideoList(response)
        }
    }

    // ==================== LATEST UPDATES ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = HttpUrl.parse("$apiBaseUrl$API_VIDEO_LIST")!!.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("order", "most-recent")
            .build()
        
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== SEARCH FUNCTIONALITY ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            // Text search
            val url = HttpUrl.parse("$apiBaseUrl$API_SEARCH")!!.newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", "50")
                .addQueryParameter("order", "most-relevant")
                .build()
            
            GET(url.toString(), headers)
        } else {
            // Filter search
            buildFilterRequest(page, filters)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString<VideoListResponse>(response.body.string())
            val animes = apiResponse.videos.map { it.toSAnime() }
            AnimesPage(animes, apiResponse.has_next)
        } catch (e: Exception) {
            parseHtmlVideoList(response)
        }
    }

    // ==================== ANIME DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            // Try API first
            val videoDetail = json.decodeFromString<VideoDetailResponse>(response.body.string())
            videoDetail.toSAnimeDetailed()
        } catch (e: Exception) {
            // Fallback to HTML parsing
            parseHtmlVideoDetails(response.asJsoup())
        }
    }

    // ==================== EPISODE LIST ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Eporner doesn't have episodes, each video is standalone
        val videoId = response.request.url.toString().substringAfterLast("/").substringBefore("?")
        return listOf(
            SEpisode.create().apply {
                name = "Full Video"
                episode_number = 1F
                setUrlWithoutDomain("$baseUrl/video/$videoId")
                date_upload = System.currentTimeMillis()
            }
        )
    }

    // ==================== VIDEO LIST/STREAMS ====================
    @SuppressLint("NewApi")
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        // Method 1: Extract from JavaScript video object
        val scriptData = document.select("script:containsData(videoObj)").firstOrNull()?.data()
        if (scriptData != null) {
            extractVideosFromScript(scriptData, document.location()).let { videos.addAll(it) }
        }
        
        // Method 2: Extract from data attributes
        document.select("[data-video-sources]").forEach { element ->
            val sourcesJson = element.attr("data-video-sources")
            extractVideosFromJson(sourcesJson, document.location()).let { videos.addAll(it) }
        }
        
        // Method 3: Check for HLS manifests
        document.select("script").forEach { script ->
            val text = script.data()
            val hlsPattern = """(https?:[^"']+\.m3u8[^"']*)""".toRegex()
            hlsPattern.findAll(text).forEach { match ->
                val hlsUrl = match.value
                extractHLSQualities(hlsUrl, document.location()).let { videos.addAll(it) }
            }
        }
        
        // Method 4: Direct MP4 links from API
        try {
            val videoId = document.selectFirst("meta[property='og:video']")
                ?.attr("content")
                ?.substringAfterLast("/")
                ?.substringBefore("?") ?: ""
            
            if (videoId.isNotEmpty()) {
                fetchVideoStreamsFromApi(videoId).let { videos.addAll(it) }
            }
        } catch (e: Exception) {
            // Continue with other methods
        }
        
        // Method 5: Fallback to iframe extraction
        if (videos.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("eporner.com/embed")) {
                    extractFromEmbedPage(src).let { videos.addAll(it) }
                }
            }
        }
        
        return videos.distinctBy { it.videoUrl }.sortedWith(videoComparator)
    }

    // ==================== RELATED ANIME ====================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val videoId = anime.url.substringAfterLast("/").substringBefore("?")
        val url = "$apiBaseUrl${API_RELATED.format(videoId)}?format=json&limit=20"
        return GET(url, headers)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        return try {
            val relatedResponse = json.decodeFromString<RelatedVideosResponse>(response.body.string())
            relatedResponse.videos.map { it.toSAnime() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== FILTER SYSTEM ====================
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            // Basic filters
            SearchQueryFilter(),
            AnimeFilter.Separator(),
            
            // Category filters
            AnimeFilter.Header("Content Categories"),
            CategoryGroupFilter(getCategories()),
            
            // Quality filters
            AnimeFilter.Header("Video Quality"),
            QualityGroupFilter(getQualities()),
            
            // Duration filters
            AnimeFilter.Header("Duration"),
            DurationGroupFilter(getDurations()),
            
            // Date filters
            AnimeFilter.Header("Upload Date"),
            DateGroupFilter(getDates()),
            
            // Sort filters
            AnimeFilter.Header("Sort By"),
            SortGroupFilter(getSortOptions()),
            
            // Advanced filters
            AnimeFilter.Header("Advanced Filters"),
            AdvancedFiltersGroup(getAdvancedFilters()),
            
            // Actor filters (dynamic loading)
            AnimeFilter.Header("Actors/Performers"),
            ActorSearchFilter(),
            
            // Studio filters
            AnimeFilter.Header("Studios/Production"),
            StudioFilter(getStudios()),
            
            // Feature toggles
            AnimeFilter.Header("Feature Toggles"),
            FeatureToggleGroup(getFeatureToggles())
        )
    }

    // ==================== PREFERENCE SCREEN ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Video quality preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Preferred Video Quality"
            entries = QUALITY_MAP.values.toTypedArray()
            entryValues = QUALITY_MAP.keys.toTypedArray()
            setDefaultValue("auto")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Sort preference
        ListPreference(screen.context).apply {
            key = PREF_SORT
            title = "Default Sort Order"
            entries = arrayOf(
                "Most Viewed",
                "Top Rated", 
                "Most Recent",
                "Most Relevant",
                "Longest",
                "Most Favorited",
                "Most Commented"
            )
            entryValues = arrayOf(
                "most-viewed",
                "top-rated",
                "most-recent",
                "most-relevant",
                "longest",
                "most-favorited",
                "most-commented"
            )
            setDefaultValue("most-viewed")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Auto-play preference
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTOPLAY
            title = "Auto-play Next Video"
            summary = "Automatically play next video when current ends"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Preview preference
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREVIEW
            title = "Enable Video Previews"
            summary = "Show video previews on hover (uses more data)"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Search suggestions
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SUGGESTIONS
            title = "Search Suggestions"
            summary = "Show search suggestions as you type"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Hide premium content
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_PREMIUM
            title = "Hide Premium Content"
            summary = "Filter out premium/paid videos"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Save history
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SAVE_HISTORY
            title = "Save Watch History"
            summary = "Remember videos you've watched (local only)"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Data usage preference
        ListPreference(screen.context).apply {
            key = "pref_data_usage"
            title = "Data Usage Mode"
            entries = arrayOf(
                "Unlimited (Best Quality)",
                "Optimized (Auto Quality)",
                "Conservation (720p Max)",
                "Extreme (480p Max)",
                "Wi-Fi Only (Full Quality)"
            )
            entryValues = arrayOf("unlimited", "optimized", "conservation", "extreme", "wifi")
            setDefaultValue("optimized")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)
        
        // Clear cache button
        Preference(screen.context).apply {
            key = "pref_clear_cache"
            title = "Clear Cache"
            summary = "Clear all cached data and thumbnails"
            setOnPreferenceClickListener {
                // Implementation would clear cache
                true
            }
        }.also(screen::addPreference)
    }

    // ==================== VIDEO SORTING ====================
    override fun List<Video>.sort(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY, "auto") ?: "auto"
        val dataMode = preferences.getString("pref_data_usage", "optimized") ?: "optimized"
        
        return sortedWith(compareByDescending<Video> { video ->
            when (qualityPref) {
                "auto" -> when (dataMode) {
                    "unlimited" -> video.qualityValue
                    "optimized" -> if (isOnWifi()) video.qualityValue else minOf(video.qualityValue, 720)
                    "conservation" -> if (video.qualityValue <= 720) video.qualityValue else 0
                    "extreme" -> if (video.qualityValue <= 480) video.qualityValue else 0
                    "wifi" -> if (isOnWifi()) video.qualityValue else 0
                    else -> video.qualityValue
                }
                else -> if (video.quality.contains(qualityPref)) 1000 + video.qualityValue else video.qualityValue
            }
        })
    }

    // ==================== UTILITY FUNCTIONS ====================
    private fun parseHtmlVideoList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("#videos .mb, .video-unit, .vid")
        
        val animeList = elements.mapNotNull { element ->
            try {
                SAnime.create().apply {
                    // Extract URL
                    val link = element.selectFirst("a")
                    link?.let {
                        val href = it.attr("href")
                        if (href.startsWith("/")) {
                            setUrlWithoutDomain("$baseUrl$href")
                        } else {
                            setUrlWithoutDomain(href)
                        }
                    }
                    
                    // Extract title
                    title = link?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: element.selectFirst(".title, h3")?.text()
                        ?: "Unknown Title"
                    
                    // Extract thumbnail
                    thumbnail_url = element.selectFirst("img")?.let { img ->
                        sequenceOf(
                            img.attr("data-src"),
                            img.attr("data-original"),
                            img.attr("src"),
                            img.attr("data-thumb_url")
                        ).firstOrNull { it.isNotBlank() }
                    }
                    
                    // Extract metadata
                    val duration = element.selectFirst(".duration, .time")?.text()
                    val views = element.selectFirst(".views")?.text()
                    val rating = element.selectFirst(".rating")?.text()
                    val hd = element.selectFirst(".hd")?.text()
                    val vr = element.selectFirst(".vr")?.text()
                    val is4k = element.selectFirst(".4k")?.text()
                    
                    // Build description
                    description = buildString {
                        duration?.let { append("⏱ $it\n") }
                        views?.let { append("👁 $it\n") }
                        rating?.let { append("⭐ $it\n") }
                        hd?.let { append("📺 $it\n") }
                        vr?.let { append("🎥 $it\n") }
                        is4k?.let { append("📈 $it\n") }
                    }.trim()
                    
                    // Extract categories/tags
                    val tags = element.select(".tags a, .categories a").eachText()
                    if (tags.isNotEmpty()) {
                        genre = tags.joinToString(", ")
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        
        // Check pagination
        val hasNextPage = document.select("""a[href*="page"]:contains(Next), 
                                           .pagination a:not(.active):last-child,
                                           a.next""").isNotEmpty()
        
        return AnimesPage(animeList, hasNextPage)
    }
    
    private fun parseHtmlVideoDetails(document: Document): SAnime {
        return SAnime.create().apply {
            // Basic info
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            
            // Metadata extraction
            val infoItems = document.select(".info .item")
            val infoMap = mutableMapOf<String, String>()
            infoItems.forEach { item ->
                val key = item.selectFirst(".label")?.text()?.trim()?.removeSuffix(":")
                val value = item.selectFirst(".value")?.text()?.trim()
                if (key != null && value != null) {
                    infoMap[key] = value
                }
            }
            
            // Build detailed description
            description = buildString {
                append(document.selectFirst(".desc")?.text() ?: "")
                append("\n\n")
                
                infoMap.forEach { (key, value) ->
                    append("$key: $value\n")
                }
                
                // Actors
                val actors = document.select(".actors a").eachText()
                if (actors.isNotEmpty()) {
                    append("\nActors: ${actors.joinToString(", ")}\n")
                }
                
                // Categories
                val categories = document.select(".categories a").eachText()
                if (categories.isNotEmpty()) {
                    append("Categories: ${categories.joinToString(", ")}\n")
                }
                
                // Tags
                val tags = document.select(".tags a").eachText()
                if (tags.isNotEmpty()) {
                    append("Tags: ${tags.joinToString(", ")}\n")
                }
            }
            
            // Set genre from categories/tags
            genre = (document.select(".categories a").eachText() + 
                    document.select(".tags a").eachText()).joinToString(", ")
            
            // Author/studio
            author = document.selectFirst(".studio a")?.text()
                ?: document.selectFirst(".production a")?.text()
            
            status = SAnime.COMPLETED
        }
    }
    
    private fun extractVideosFromScript(script: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Pattern 1: videoObj JSON
        val videoObjPattern = """videoObj\s*=\s*(\{[^;]+\})""".toRegex()
        videoObjPattern.find(script)?.groupValues?.get(1)?.let { jsonStr ->
            try {
                val jsonObject = Json.parseToJsonElement(jsonStr).jsonObject
                jsonObject.forEach { (quality, urlElement) ->
                    val url = urlElement.jsonPrimitive.content
                    if (url.isNotBlank() && !url.contains("null")) {
                        videos.add(Video(url, quality, url, headers))
                    }
                }
            } catch (e: Exception) {
                // Try alternative parsing
            }
        }
        
        // Pattern 2: Direct quality URLs
        val qualityPattern = """["']?(\d+)p["']?\s*:\s*["']([^"']+)["']""".toRegex()
        qualityPattern.findAll(script).forEach { match ->
            val quality = "${match.groupValues[1]}p"
            val url = match.groupValues[2]
            if (url.isNotBlank() && !url.contains("null")) {
                videos.add(Video(url, quality, url, headers))
            }
        }
        
        // Pattern 3: MP4 sources
        val mp4Pattern = """(https?:[^"']+\.mp4[^"']*)""".toRegex()
        mp4Pattern.findAll(script).forEach { match ->
            val url = match.value
            val quality = when {
                url.contains("2160") -> "2160p"
                url.contains("1440") -> "1440p"
                url.contains("1080") -> "1080p"
                url.contains("720") -> "720p"
                url.contains("480") -> "480p"
                url.contains("360") -> "360p"
                url.contains("240") -> "240p"
                else -> "Unknown"
            }
            videos.add(Video(url, quality, url, headers))
        }
        
        return videos
    }
    
    private fun extractHLSQualities(hlsUrl: String, referer: String): List<Video> {
        return try {
            playlistUtils.extractFromHls(hlsUrl, referer, videoNameGen = { "HLS - $it" })
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun fetchVideoStreamsFromApi(videoId: String): List<Video> {
        return try {
            val url = "$apiBaseUrl$API_VIDEO_DETAIL$videoId/streams/?format=json"
            val response = client.newCall(GET(url, headers)).execute()
            val streams = json.decodeFromString<VideoStreamsResponse>(response.body.string())
            
            streams.qualities.mapNotNull { (quality, url) ->
                if (url.isNotBlank()) {
                    Video(url, quality, url, headers)
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun extractFromEmbedPage(embedUrl: String): List<Video> {
        return try {
            val response = client.newCall(GET(embedUrl, headers)).execute()
            val document = response.asJsoup()
            
            val videos = mutableListOf<Video>()
            
            // Check for video sources in embed
            document.select("source").forEach { source ->
                val src = source.attr("src")
                if (src.isNotEmpty()) {
                    val quality = source.attr("label") ?: 
                                 source.attr("title") ?: 
                                 src.substringAfterLast("/").substringBefore(".").takeIf { 
                                     it.all { c -> c.isDigit() } 
                                 }?.let { "${it}p" } ?: "Unknown"
                    videos.add(Video(src, "Embed - $quality", src, headers))
                }
            }
            
            // Check for iframe within iframe (nested)
            document.select("iframe").forEach { iframe ->
                val nestedUrl = iframe.attr("src")
                if (nestedUrl.isNotEmpty() && nestedUrl != embedUrl) {
                    extractFromEmbedPage(nestedUrl).let { videos.addAll(it) }
                }
            }
            
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun buildFilterRequest(page: Int, filters: AnimeFilterList): Request {
        val url = HttpUrl.parse("$apiBaseUrl$API_VIDEO_LIST")!!.newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
        
        filters.forEach { filter ->
            when (filter) {
                is SearchQueryFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("query", filter.state)
                    }
                }
                is CategoryGroupFilter -> {
                    filter.state.forEach { category ->
                        if (category.state != 0) {
                            url.addQueryParameter("category", category.toUriPart())
                        }
                    }
                }
                is QualityGroupFilter -> {
                    filter.state.forEach { quality ->
                        if (quality.state != 0) {
                            url.addQueryParameter("quality", quality.toUriPart())
                        }
                    }
                }
                is DurationGroupFilter -> {
                    filter.state.forEach { duration ->
                        if (duration.state != 0) {
                            url.addQueryParameter("duration", duration.toUriPart())
                        }
                    }
                }
                is DateGroupFilter -> {
                    filter.state.forEach { date ->
                        if (date.state != 0) {
                            url.addQueryParameter("date", date.toUriPart())
                        }
                    }
                }
                is SortGroupFilter -> {
                    if (filter.state != 0) {
                        url.setQueryParameter("order", filter.toUriPart())
                    }
                }
                // Handle other filters...
            }
        }
        
        return GET(url.build().toString(), headers)
    }
    
    // ==================== DATA CLASSES ====================
    @Serializable
    data class VideoListResponse(
        @SerialName("videos") val videos: List<VideoItem>,
        @SerialName("has_next") val has_next: Boolean,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
        @SerialName("total_videos") val total_videos: Int
    )
    
    @Serializable
    data class VideoItem(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("duration") val duration: Int,
        @SerialName("views") val views: Int,
        @SerialName("rating") val rating: Float,
        @SerialName("thumbnail") val thumbnail: String,
        @SerialName("is_hd") val is_hd: Boolean,
        @SerialName("is_vr") val is_vr: Boolean,
        @SerialName("is_4k") val is_4k: Boolean,
        @SerialName("upload_date") val upload_date: String,
        @SerialName("categories") val categories: List<String>? = null,
        @SerialName("actors") val actors: List<ActorItem>? = null
    ) {
        fun toSAnime(): SAnime {
            return SAnime.create().apply {
                setUrlWithoutDomain("https://www.eporner.com/video/$id/")
                this.title = title
                thumbnail_url = thumbnail
                description = buildString {
                    append("Duration: ${duration / 60}min\n")
                    append("Views: ${views.formatWithCommas()}\n")
                    append("Rating: ${rating}/5\n")
                    append("Uploaded: ${upload_date}\n")
                    if (is_hd) append("HD\n")
                    if (is_vr) append("VR\n")
                    if (is_4k) append("4K\n")
                    categories?.let { append("Categories: ${it.joinToString(", ")}\n") }
                    actors?.let { append("Actors: ${it.joinToString(", ") { actor -> actor.name }}\n") }
                }
                genre = categories?.joinToString(", ") ?: ""
                status = SAnime.COMPLETED
            }
        }
    }
    
    @Serializable
    data class VideoDetailResponse(
        @SerialName("video") val video: VideoDetail
    ) {
        fun toSAnimeDetailed(): SAnime {
            return SAnime.create().apply {
                setUrlWithoutDomain("https://www.eporner.com/video/${video.id}/")
                title = video.title
                thumbnail_url = video.thumbnail
                description = buildString {
                    append(video.description ?: "")
                    append("\n\n")
                    append("Duration: ${video.duration / 60} minutes\n")
                    append("Views: ${video.views.formatWithCommas()}\n")
                    append("Rating: ${video.rating}/5 (${video.votes} votes)\n")
                    append("Uploaded: ${video.upload_date}\n")
                    append("Quality: ")
                    if (video.is_4k) append("4K ")
                    if (video.is_hd) append("HD ")
                    if (video.is_vr) append("VR ")
                    if (video.is_60fps) append("60FPS ")
                    append("\n")
                    video.categories?.let { append("Categories: ${it.joinToString(", ")}\n") }
                    video.actors?.let { append("Actors: ${it.joinToString(", ") { actor -> actor.name }}\n") }
                    video.tags?.let { append("Tags: ${it.joinToString(", ")}\n") }
                    video.production?.let { prod ->
                        prod.studio?.let { append("Studio: $it\n") }
                        prod.series?.let { append("Series: $it\n") }
                        prod.director?.let { append("Director: $it\n") }
                    }
                }
                genre = video.categories?.joinToString(", ") ?: ""
                author = video.production?.studio
                status = SAnime.COMPLETED
            }
        }
    }
    
    @Serializable
    data class VideoDetail(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("description") val description: String?,
        @SerialName("duration") val duration: Int,
        @SerialName("views") val views: Int,
        @SerialName("rating") val rating: Float,
        @SerialName("votes") val votes: Int,
        @SerialName("thumbnail") val thumbnail: String,
        @SerialName("is_hd") val is_hd: Boolean,
        @SerialName("is_vr") val is_vr: Boolean,
        @SerialName("is_4k") val is_4k: Boolean,
        @SerialName("is_60fps") val is_60fps: Boolean,
        @SerialName("upload_date") val upload_date: String,
        @SerialName("categories") val categories: List<String>?,
        @SerialName("actors") val actors: List<ActorItem>?,
        @SerialName("tags") val tags: List<String>?,
        @SerialName("production") val production: ProductionInfo?
    )
    
    @Serializable
    data class ActorItem(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("gender") val gender: String?,
        @SerialName("thumbnail") val thumbnail: String?
    )
    
    @Serializable
    data class ProductionInfo(
        @SerialName("studio") val studio: String?,
        @SerialName("series") val series: String?,
        @SerialName("director") val director: String?,
        @SerialName("release_date") val release_date: String?
    )
    
    @Serializable
    data class VideoStreamsResponse(
        @SerialName("qualities") val qualities: Map<String, String>,
        @SerialName("hls_manifest") val hls_manifest: String?
    )
    
    @Serializable
    data class RelatedVideosResponse(
        @SerialName("videos") val videos: List<VideoItem>
    )
    
    // ==================== FILTER CLASSES ====================
    class SearchQueryFilter : AnimeFilter.Text("Search Query")
    
    open class UriPartFilter(
        name: String,
        private val vals: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
    
    class CategoryGroupFilter(categories: List<CategoryFilter>) : 
        AnimeFilter.Group<CategoryFilter>("Categories", categories)
    
    class CategoryFilter(name: String, vals: Array<Pair<String, String>>) : 
        UriPartFilter(name, vals)
    
    class QualityGroupFilter(qualities: List<QualityFilter>) : 
        AnimeFilter.Group<QualityFilter>("Qualities", qualities)
    
    class QualityFilter(name: String, vals: Array<Pair<String, String>>) : 
        UriPartFilter(name, vals)
    
    class DurationGroupFilter(durations: List<DurationFilter>) : 
        AnimeFilter.Group<DurationFilter>("Durations", durations)
    
    class DurationFilter(name: String, vals: Array<Pair<String, String>>) : 
        UriPartFilter(name, vals)
    
    class DateGroupFilter(dates: List<DateFilter>) : 
        AnimeFilter.Group<DateFilter>("Dates", dates)
    
    class DateFilter(name: String, vals: Array<Pair<String, String>>) : 
        UriPartFilter(name, vals)
    
    class SortGroupFilter(sorts: List<SortFilter>) : 
        AnimeFilter.Group<SortFilter>("Sort Options", sorts)
    
    class SortFilter(name: String, vals: Array<Pair<String, String>>) : 
        UriPartFilter(name, vals)
    
    class AdvancedFiltersGroup(filters: List<AdvancedFilter>) : 
        AnimeFilter.Group<AdvancedFilter>("Advanced", filters)
    
    class AdvancedFilter(name: String) : AnimeFilter.CheckBox(name)
    
    class ActorSearchFilter : AnimeFilter.Text("Actor Search")
    
    class StudioFilter(studios: List<StudioCheckBox>) : 
        AnimeFilter.Group<StudioCheckBox>("Studios", studios)
    
    class StudioCheckBox(name: String) : AnimeFilter.CheckBox(name)
    
    class FeatureToggleGroup(features: List<FeatureToggle>) : 
        AnimeFilter.Group<FeatureToggle>("Features", features)
    
    class FeatureToggle(name: String) : AnimeFilter.CheckBox(name)
    
    // ==================== DATA PROVIDERS ====================
    private fun getCategories(): List<CategoryFilter> {
        return listOf(
            CategoryFilter("Amateur", arrayOf(Pair("Amateur", "amateur"))),
            CategoryFilter("Anal", arrayOf(Pair("Anal", "anal"))),
            CategoryFilter("Asian", arrayOf(Pair("Asian", "asian"))),
            CategoryFilter("BBC", arrayOf(Pair("BBC", "bbc"))),
            CategoryFilter("BBW", arrayOf(Pair("BBW", "bbw"))),
            CategoryFilter("BDSM", arrayOf(Pair("BDSM", "bdsm"))),
            CategoryFilter("Big Tits", arrayOf(Pair("Big Tits", "big-tits"))),
            CategoryFilter("Blonde", arrayOf(Pair("Blonde", "blonde"))),
            CategoryFilter("Blowjob", arrayOf(Pair("Blowjob", "blowjob"))),
            CategoryFilter("Brunette", arrayOf(Pair("Brunette", "brunette"))),
            CategoryFilter("Bukkake", arrayOf(Pair("Bukkake", "bukkake"))),
            CategoryFilter("College", arrayOf(Pair("College", "college"))),
            CategoryFilter("Creampie", arrayOf(Pair("Creampie", "creampie"))),
            CategoryFilter("Cumshot", arrayOf(Pair("Cumshot", "cumshot"))),
            CategoryFilter("Double Penetration", arrayOf(Pair("DP", "double-penetration"))),
            CategoryFilter("Ebony", arrayOf(Pair("Ebony", "ebony"))),
            CategoryFilter("Gangbang", arrayOf(Pair("Gangbang", "gangbang"))),
            CategoryFilter("Hardcore", arrayOf(Pair("Hardcore", "hardcore"))),
            CategoryFilter("Japanese", arrayOf(Pair("Japanese", "japanese"))),
            CategoryFilter("Latina", arrayOf(Pair("Latina", "latina"))),
            CategoryFilter("Lesbian", arrayOf(Pair("Lesbian", "lesbian"))),
            CategoryFilter("MILF", arrayOf(Pair("MILF", "milf"))),
            CategoryFilter("Teen", arrayOf(Pair("Teen", "teen"))),
            CategoryFilter("Threesome", arrayOf(Pair("Threesome", "threesome"))),
            CategoryFilter("VR", arrayOf(Pair("VR", "vr"))),
            CategoryFilter("4K", arrayOf(Pair("4K", "4k")))
        )
    }
    
    private fun getQualities(): List<QualityFilter> {
        return listOf(
            QualityFilter("4K", arrayOf(Pair("4K", "4k"))),
            QualityFilter("1080p", arrayOf(Pair("1080p", "1080p"))),
            QualityFilter("720p", arrayOf(Pair("720p", "720p"))),
            QualityFilter("480p", arrayOf(Pair("480p", "480p"))),
            QualityFilter("360p", arrayOf(Pair("360p", "360p"))),
            QualityFilter("240p", arrayOf(Pair("240p", "240p")))
        )
    }
    
    private fun getDurations(): List<DurationFilter> {
        return listOf(
            DurationFilter("Short (<10min)", arrayOf(Pair("Short", "0-10"))),
            DurationFilter("Medium (10-20min)", arrayOf(Pair("Medium", "10-20"))),
            DurationFilter("Long (20-30min)", arrayOf(Pair("Long", "20-30"))),
            DurationFilter("Extra Long (30+min)", arrayOf(Pair("Extra Long", "30+")))
        )
    }
    
    private fun getDates(): List<DateFilter> {
        return listOf(
            DateFilter("Today", arrayOf(Pair("Today", "1"))),
            DateFilter("This Week", arrayOf(Pair("This Week", "7"))),
            DateFilter("This Month", arrayOf(Pair("This Month", "30"))),
            DateFilter("This Year", arrayOf(Pair("This Year", "365"))),
            DateFilter("All Time", arrayOf(Pair("All Time", "all")))
        )
    }
    
    private fun getSortOptions(): List<SortFilter> {
        return listOf(
            SortFilter("Most Viewed", arrayOf(Pair("Most Viewed", "most-viewed"))),
            SortFilter("Top Rated", arrayOf(Pair("Top Rated", "top-rated"))),
            SortFilter("Most Recent", arrayOf(Pair("Most Recent", "most-recent"))),
            SortFilter("Longest", arrayOf(Pair("Longest", "longest"))),
            SortFilter("Most Favorited", arrayOf(Pair("Most Favorited", "most-favorited"))),
            SortFilter("Most Commented", arrayOf(Pair("Most Commented", "most-commented")))
        )
    }
    
    private fun getStudios(): List<StudioCheckBox> {
        return listOf(
            StudioCheckBox("Brazzers"),
            StudioCheckBox("Reality Kings"),
            StudioCheckBox("Naughty America"),
            StudioCheckBox("Bang Bros"),
            StudioCheckBox("Team Skeet"),
            StudioCheckBox("Mofos"),
            StudioCheckBox("Blacked"),
            StudioCheckBox("Tushy"),
            StudioCheckBox("Vixen"),
            StudioCheckBox("Public Agent"),
            StudioCheckBox("Dogfart"),
            StudioCheckBox("Kink.com"),
            StudioCheckBox("Jules Jordan"),
            StudioCheckBox("Evil Angel"),
            StudioCheckBox("Digital Playground")
        )
    }
    
    private fun getAdvancedFilters(): List<AdvancedFilter> {
        return listOf(
            AdvancedFilter("HD Only"),
            AdvancedFilter("VR Only"),
            AdvancedFilter("4K Only"),
            AdvancedFilter("60 FPS Only"),
            AdvancedFilter("Exclude Amateur"),
            AdvancedFilter("Exclude Professional"),
            AdvancedFilter("Has Subtitles"),
            AdvancedFilter("Has Storyline")
        )
    }
    
    private fun getFeatureToggles(): List<FeatureToggle> {
        return listOf(
            FeatureToggle("Show Previews"),
            FeatureToggle("Auto Quality"),
            FeatureToggle("Skip Intros"),
            FeatureToggle("Background Play"),
            FeatureToggle("Download in Background"),
            FeatureToggle("Notifications for New Videos"),
            FeatureToggle("Parental Controls"),
            FeatureToggle("Incognito Mode")
        )
    }
    
    // ==================== INTERCEPTORS ====================
    class UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            return chain.proceed(request)
        }
    }
    
    class RefererInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val newRequest = request.newBuilder()
                .header("Referer", "https://www.eporner.com/")
                .header("Origin", "https://www.eporner.com")
                .build()
            return chain.proceed(newRequest)
        }
    }
    
    class CacheInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            
            return response.newBuilder()
                .header("Cache-Control", "public, max-age=3600")
                .header("Vary", "Accept-Encoding")
                .build()
        }
    }
    
    // ==================== UTILITY EXTENSIONS ====================
    private fun Int.formatWithCommas(): String {
        return String.format("%,d", this)
    }
    
    private val Video.qualityValue: Int
        get() = Regex("""(\d+)""").find(this.quality)?.value?.toIntOrNull() ?: 0
    
    private val videoComparator = Comparator<Video> { v1, v2 ->
        v2.qualityValue.compareTo(v1.qualityValue)
    }
    
    private fun isOnWifi(): Boolean {
        // This would require Android context
        // For now, return true as most users will be on WiFi for porn streaming
        return true
    }
    
    private fun extractVideosFromJson(jsonStr: String, referer: String): List<Video> {
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            json.mapNotNull { (quality, urlElement) ->
                val url = urlElement.jsonPrimitive.content
                if (url.isNotBlank()) {
                    Video(url, quality, url, headers)
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ==================== ENCRYPTION UTILITIES (for premium content) ====================
    private fun decryptPremiumUrl(encrypted: String): String? {
        return try {
            // Eporner uses simple Base64 for some encoded URLs
            String(Base64.decode(encrypted, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }
    
    // ==================== ERROR HANDLING ====================
    private fun handle404Error(url: String): Boolean {
        // Check if URL is likely to 404
        val invalidPatterns = listOf(
            "undefined",
            "null",
            "NaN",
            "//",
            "http:/",
            "https:/"
        )
        return invalidPatterns.any { url.contains(it) }
    }
}

// ==================== BUILD.GRADLE CONFIGURATION ====================
/*
ext {
    extName = 'Eporner'
    extClass = '.Eporner'
    extVersionCode = 1
    isNsfw = true
}

apply from: "$rootDir/common.gradle"

dependencies {
    implementation(project(':lib:playlist-utils'))
    implementation(project(':lib:unpacker'))
    implementation(libs.json)
    implementation(libs.kotlinx.serialization.json)
}
*/
