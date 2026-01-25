package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.preference.*
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true
    override val supportsRelatedAnimes = true

    // ==================== CLIENT CONFIGURATION ====================
    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .apply {
                    // Standard headers for Eporner
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    header("Accept-Language", "en-US,en;q=0.9")
                    header("Accept-Encoding", "gzip, deflate, br")
                    header("DNT", "1")
                    header("Connection", "keep-alive")
                    header("Upgrade-Insecure-Requests", "1")
                    header("Sec-Fetch-Dest", "document")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "same-origin")
                    header("Sec-Fetch-User", "?1")
                    
                    // Add Referer for video requests
                    val referer = chain.request().header("Referer") ?: baseUrl
                    header("Referer", referer)
                }
                .build()
            chain.proceed(request)
        }
        .build()

    // ==================== PREFERENCES ====================
    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context as Context)
    }

    // ==================== EXTRACTORS ====================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    // ==================== POPULAR ANIME ====================
    override fun popularAnimeRequest(page: Int): Request {
        val sort = preferences.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT) ?: PREF_SORT_DEFAULT
        val url = when (sort) {
            "most-viewed" -> "$baseUrl/hd-porn/$page/"
            "top-rated" -> "$baseUrl/top-rated/$page/"
            "longest" -> "$baseUrl/longest/$page/"
            else -> "$baseUrl/hd-porn/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseVideoListing(response.asJsoup())
    }

    // ==================== LATEST UPDATES ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-updates/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseVideoListing(response.asJsoup())
    }

    // ==================== SEARCH FUNCTIONALITY ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            // Text search
            val encodedQuery = query.lowercase()
                .replace("[^a-z0-9\\s-]".toRegex(), "")
                .replace("\\s+".toRegex(), "-")
                .trim('-')
            GET("$baseUrl/search/$encodedQuery/$page/", headers)
        } else {
            // Filter-based search
            buildFilterUrl(page, filters)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseVideoListing(response.asJsoup())
    }

    // ==================== VIDEO LISTING PARSER ====================
    private fun parseVideoListing(document: org.jsoup.nodes.Document): AnimesPage {
        val elements = document.select("#videos .mb, .video-unit, .vid")
        
        val animeList = elements.mapNotNull { element ->
            try {
                SAnime.create().apply {
                    // Extract video URL
                    val link = element.selectFirst("a")
                    link?.let {
                        val href = it.attr("href")
                        setUrlWithoutDomain(
                            if (href.startsWith("/")) "$baseUrl$href"
                            else if (href.startsWith("http")) href
                            else "$baseUrl/$href"
                        )
                    }

                    // Extract title
                    title = link?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: element.selectFirst(".title, h3")?.text()
                        ?: element.selectFirst(".duration")?.parent()?.text()
                        ?: "Unknown Title"

                    // Enhanced thumbnail with preview support
                    val enablePreviews = preferences.getBoolean(PREF_PREVIEW_KEY, PREF_PREVIEW_DEFAULT)
                    thumbnail_url = extractBestThumbnail(element, enablePreviews)

                    // Extract metadata
                    val duration = element.selectFirst(".duration, .time")?.text()
                    val views = element.selectFirst(".views")?.text()
                    val rating = element.selectFirst(".rating")?.text()
                    val isHD = element.selectFirst(".hd, .is-hd") != null
                    val isVR = element.selectFirst(".vr, .is-vr") != null
                    val is4K = element.selectFirst(".uhd, .is-4k, .fourk") != null
                    val is60fps = element.selectFirst(".fps, .sixtyfps") != null

                    // Build description with metadata
                    description = buildString {
                        duration?.let { append("⏱ $it\n") }
                        views?.let { append("👁 ${it.formatViews()}\n") }
                        rating?.let { append("⭐ ${it.formatRating()}\n") }
                        if (isHD) append("🎬 HD\n")
                        if (isVR) append("👓 VR\n")
                        if (is4K) append("📺 4K\n")
                        if (is60fps) append("⚡ 60 FPS\n")
                    }.trim()

                    // Extract video ID for advanced features
                    val videoId = link?.attr("href")?.substringAfterLast("/")?.substringBefore("?")
                    if (videoId != null) {
                        // Store metadata for later use
                        initialized = true
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        // Pagination detection
        val hasNextPage = document.select("""a[href*="page"], a.next, 
                                           .pagination a:not(.active):last-child,
                                           a:contains(Next)""").any { element ->
            val href = element.attr("href")
            href.contains("page") || element.text().contains("Next", ignoreCase = true)
        }

        return AnimesPage(animeList, hasNextPage)
    }

    private fun extractBestThumbnail(element: org.jsoup.nodes.Element, enablePreviews: Boolean): String {
        return element.selectFirst("img")?.let { img ->
            // Priority order for thumbnails
            val sources = listOfNotNull(
                // Preview GIFs (animated)
                if (enablePreviews) img.attr("data-preview").takeIf { it.isNotBlank() } else null,
                if (enablePreviews) img.attr("data-gif").takeIf { it.isNotBlank() } else null,
                
                // High-quality static images
                img.attr("data-src").takeIf { it.isNotBlank() },
                img.attr("data-original").takeIf { it.isNotBlank() },
                img.attr("src").takeIf { it.isNotBlank() },
                
                // Fallback: extract from style attribute
                img.attr("style").substringAfter("url('").substringBefore("')").takeIf { it.isNotBlank() }
            )
            
            sources.firstOrNull() ?: ""
        } ?: ""
    }

    // ==================== ANIME DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        
        return SAnime.create().apply {
            // Basic Information
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst(".main-video img")?.attr("src")
                ?: document.selectFirst("#player video")?.attr("poster")

            // Detailed Metadata Extraction
            val metadata = extractVideoMetadata(document)
            
            // Build comprehensive description
            description = buildDescription(document, metadata)
            
            // Extract genres/categories
            genre = extractGenres(document)
            
            // Extract actors/performers
            artist = extractActors(document).joinToString(", ")
            
            // Extract studio/production
            author = extractStudio(document)
            
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    private fun extractVideoMetadata(document: org.jsoup.nodes.Document): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        
        // Extract from info items
        document.select(".info .item, .video-info .item").forEach { item ->
            val key = item.selectFirst(".label, .key")?.text()?.trim()?.removeSuffix(":")
            val value = item.selectFirst(".value, .val")?.text()?.trim()
            if (key != null && value != null) {
                metadata[key] = value
            }
        }
        
        // Extract from meta tags
        document.select("meta[itemprop]").forEach { meta ->
            val key = meta.attr("itemprop")
            val value = meta.attr("content")
            if (key.isNotBlank() && value.isNotBlank()) {
                metadata[key] = value
            }
        }
        
        return metadata
    }

    private fun buildDescription(document: org.jsoup.nodes.Document, metadata: Map<String, String>): String {
        return buildString {
            // Main description
            document.selectFirst(".desc, .description, #description")?.text()?.let {
                append("📝 $it\n\n")
            }
            
            // Metadata section
            if (metadata.isNotEmpty()) {
                append("📊 Metadata:\n")
                metadata.forEach { (key, value) ->
                    append("  • ${key.capitalize()}: $value\n")
                }
                append("\n")
            }
            
            // Quality info
            val qualities = mutableListOf<String>()
            if (document.selectFirst(".hd, [data-hd]") != null) qualities.add("HD")
            if (document.selectFirst(".vr, [data-vr]") != null) qualities.add("VR")
            if (document.selectFirst(".uhd, .fourk, [data-4k]") != null) qualities.add("4K")
            if (document.selectFirst(".fps, [data-60fps]") != null) qualities.add("60 FPS")
            if (qualities.isNotEmpty()) {
                append("🎬 Quality: ${qualities.joinToString(", ")}\n")
            }
            
            // Statistics
            val views = document.selectFirst(".views, [itemprop='interactionCount']")?.text()
            val rating = document.selectFirst(".rating, [itemprop='ratingValue']")?.text()
            val duration = document.selectFirst(".duration, [itemprop='duration']")?.text()
            
            if (views != null || rating != null || duration != null) {
                append("\n📈 Statistics:\n")
                views?.let { append("  • Views: ${it.formatViews()}\n") }
                rating?.let { append("  • Rating: ${it.formatRating()}\n") }
                duration?.let { append("  • Duration: $it\n") }
            }
        }.trim()
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): String {
        return document.select(".categories a, .tags a, .genres a, [rel='tag']")
            .eachText()
            .distinct()
            .joinToString(", ")
    }

    private fun extractActors(document: org.jsoup.nodes.Document): List<String> {
        return document.select(".actors a, .pornstars a, .models a, [rel='actor']")
            .eachText()
            .distinct()
    }

    private fun extractStudio(document: org.jsoup.nodes.Document): String {
        return document.selectFirst(".studio a, .production a, [rel='studio']")?.text()
            ?: document.selectFirst("a[href*='/studio/']")?.text()
            ?: ""
    }

    // ==================== EPISODE LIST ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Full Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
                date_upload = extractUploadDate(response.asJsoup())
            }
        )
    }

    private fun extractUploadDate(document: org.jsoup.nodes.Document): Long {
        return try {
            document.selectFirst("span[itemprop='uploadDate'], .upload-date, time")?.let { dateElement ->
                val dateText = dateElement.attr("datetime") ?: dateElement.text()
                parseDateString(dateText)
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // ==================== VIDEO EXTRACTION ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        // Strategy 1: Extract from video data attributes
        extractFromDataAttributes(document).let { videos.addAll(it) }
        
        // Strategy 2: Extract from JavaScript variables
        if (videos.isEmpty()) {
            extractFromJavaScript(document).let { videos.addAll(it) }
        }
        
        // Strategy 3: Extract from embed iframes
        if (videos.isEmpty()) {
            document.select("iframe").mapNotNull { it.attr("src") }
                .parallelCatchingFlatMapBlocking { extractFromEmbedUrl(it) }
                .let { videos.addAll(it) }
        }
        
        // Strategy 4: Extract from HTML5 video sources
        document.select("video source, source[type*='video']").forEach { source ->
            val url = source.attr("src").takeIf { it.isNotBlank() }
            val quality = source.attr("label") ?: extractQualityFromUrl(url ?: "")
            
            url?.let {
                videos.add(Video(it, "Direct - $quality", it, headers))
            }
        }
        
        // Strategy 5: Extract from API endpoints
        if (videos.isEmpty()) {
            extractFromApiEndpoints(document).let { videos.addAll(it) }
        }
        
        return videos.distinctBy { it.quality to it.videoUrl }
            .sortedWith(compareByDescending<Video> { it.qualityValue })
    }

    private fun extractFromDataAttributes(document: org.jsoup.nodes.Document): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Look for data-video attributes
        document.select("[data-video], [data-src], [data-url]").forEach { element ->
            val url = element.attr("data-video").takeIf { it.isNotBlank() }
                ?: element.attr("data-src").takeIf { it.isNotBlank() }
                ?: element.attr("data-url").takeIf { it.isNotBlank() }
            
            url?.let {
                val quality = element.attr("data-quality") ?: extractQualityFromUrl(it)
                videos.add(Video(it, "Data - $quality", it, headers))
            }
        }
        
        return videos
    }

    private fun extractFromJavaScript(document: org.jsoup.nodes.Document): List<Video> {
        val videos = mutableListOf<Video>()
        
        document.select("script").forEach { script ->
            val scriptText = script.data() + script.html()
            
            // Extract video URLs from JavaScript variables
            val patterns = listOf(
                """videoUrl\s*[:=]\s*["']([^"']+)["']""",
                """src\s*[:=]\s*["']([^"']+)["']""",
                """file\s*[:=]\s*["']([^"']+)["']""",
                """url\s*[:=]\s*["']([^"']+)["']"""
            )
            
            patterns.forEach { pattern ->
                pattern.toRegex().findAll(scriptText).forEach { match ->
                    val url = match.groupValues[1].takeIf { it.isNotBlank() }
                    url?.let {
                        if (it.contains("mp4") || it.contains("m3u8") || it.contains("video")) {
                            val quality = extractQualityFromUrl(it)
                            videos.add(Video(it, "JS - $quality", it, headers))
                        }
                    }
                }
            }
        }
        
        return videos
    }

    private fun extractFromEmbedUrl(embedUrl: String): List<Video> {
        return try {
            when {
                embedUrl.contains("streamwish") -> streamwishExtractor.videosFromUrl(embedUrl) ?: emptyList()
                embedUrl.contains("voe") -> voeExtractor.videosFromUrl(embedUrl) ?: emptyList()
                embedUrl.contains("dood") -> doodExtractor.videosFromUrl(embedUrl) ?: emptyList()
                embedUrl.contains("streamtape") -> streamtapeExtractor.videoFromUrl(embedUrl)?.let { listOf(it) } ?: emptyList()
                embedUrl.contains("ok.ru") -> okruExtractor.videosFromUrl(embedUrl) ?: emptyList()
                embedUrl.contains("mixdrop") -> mixdropExtractor.videoFromUrl(embedUrl)?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractFromApiEndpoints(document: org.jsoup.nodes.Document): List<Video> {
        val videos = mutableListOf<Video>()
        val videoId = document.location().substringAfterLast("/").substringBefore("?")
        
        if (videoId.isNotBlank()) {
            // Try various API patterns
            val apiPatterns = listOf(
                "$baseUrl/api/video/$videoId",
                "$baseUrl/api/player/$videoId",
                "$baseUrl/video/$videoId/json"
            )
            
            apiPatterns.forEach { apiUrl ->
                try {
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    if (response.isSuccessful) {
                        val json = response.body.string()
                        extractVideosFromJson(json).let { videos.addAll(it) }
                    }
                } catch (e: Exception) {
                    // Continue to next pattern
                }
            }
        }
        
        return videos
    }

    private fun extractVideosFromJson(json: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Simple JSON parsing for video URLs
        val urlPattern = """["']?(?:url|src|file)["']?\s*:\s*["']([^"']+)["']""".toRegex()
        val qualityPattern = """["']?(?:quality|res|height)["']?\s*:\s*["']?([^,"'}]+)["']?""".toRegex()
        
        urlPattern.findAll(json).forEachIndexed { index, urlMatch ->
            val url = urlMatch.groupValues[1]
            if (url.isNotBlank() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                // Try to find corresponding quality
                val quality = run {
                    qualityPattern.find(json, urlMatch.range.first)?.groupValues?.get(1)
                        ?: extractQualityFromUrl(url)
                }
                videos.add(Video(url, "API - $quality", url, headers))
            }
        }
        
        return videos
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("2160") || url.contains("4k") -> "4K"
            url.contains("1440") -> "1440p"
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            url.contains(".m3u8") -> "HLS"
            else -> "Unknown"
        }
    }

    // ==================== RELATED ANIME ====================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val videoId = anime.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/video/$videoId/related/", headers)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        return document.select(".related-videos .video, #related .mb").mapNotNull { element ->
            try {
                SAnime.create().apply {
                    val link = element.selectFirst("a")
                    link?.let {
                        setUrlWithoutDomain(
                            if (it.attr("href").startsWith("/")) "$baseUrl${it.attr("href")}"
                            else it.attr("href")
                        )
                    }
                    title = link?.attr("title") ?: element.selectFirst("img")?.attr("alt") ?: ""
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ==================== FILTER URL BUILDER ====================
    private fun buildFilterUrl(page: Int, filters: AnimeFilterList): Request {
        val urlBuilder = HttpUrl.parse("$baseUrl/filter/")?.newBuilder()
            ?: return GET("$baseUrl/$page/", headers)
        
        // Add filter parameters
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                }
                is CategoryFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("category", filter.toUriPart())
                    }
                }
                is QualityFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("quality", filter.toUriPart())
                    }
                }
                is DurationFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("duration", filter.toUriPart())
                    }
                }
                is DateFilter -> {
                    if (filter.state != 0) {
                        urlBuilder.addQueryParameter("date", filter.toUriPart())
                    }
                }
                is HDOnlyFilter -> {
                    if (filter.state) {
                        urlBuilder.addQueryParameter("hd", "1")
                    }
                }
                is VRFilter -> {
                    if (filter.state) {
                        urlBuilder.addQueryParameter("vr", "1")
                    }
                }
                is ActorFilter -> {
                    if (filter.state.isNotBlank()) {
                        urlBuilder.addQueryParameter("actor", filter.state)
                    }
                }
            }
        }
        
        // Add pagination
        urlBuilder.addQueryParameter("page", page.toString())
        
        return GET(urlBuilder.build().toString(), headers)
    }

    // ==================== PREFERENCE SCREEN ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Video Quality Preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Video Quality"
            entries = arrayOf(
                "Auto (Best Available)",
                "4K (2160p)",
                "QHD (1440p)",
                "Full HD (1080p)",
                "HD (720p)",
                "SD (480p)",
                "Low (360p)",
                "Mobile (240p)"
            )
            entryValues = arrayOf("auto", "2160", "1440", "1080", "720", "480", "360", "240")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        // Sort Preference
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default Sort Order"
            entries = arrayOf(
                "Most Viewed",
                "Top Rated",
                "Most Recent",
                "Longest",
                "Most Favorited",
                "Most Commented"
            )
            entryValues = arrayOf("most-viewed", "top-rated", "latest", "longest", "most-favorited", "most-commented")
            setDefaultValue(PREF_SORT_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        // Video Preview Preference
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PREVIEW_KEY
            title = "Enable Video Previews"
            summary = "Show animated previews/GIFs for videos (uses more data)"
            setDefaultValue(PREF_PREVIEW_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        // Auto-play Preference
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTOPLAY_KEY
            title = "Auto-play Next Video"
            summary = "Automatically play next video when current ends"
            setDefaultValue(PREF_AUTOPLAY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        // Data Saving Preference
        ListPreference(screen.context).apply {
            key = PREF_DATA_SAVING_KEY
            title = "Data Saving Mode"
            entries = arrayOf(
                "Disabled (Best Quality)",
                "Wi-Fi Only (Full Quality)",
                "Enabled (720p Max)",
                "Extreme (480p Max)",
                "Audio Only"
            )
            entryValues = arrayOf("disabled", "wifi", "enabled", "extreme", "audio")
            setDefaultValue(PREF_DATA_SAVING_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        // Video Source Priority
        MultiSelectListPreference(screen.context).apply {
            key = PREF_SOURCE_PRIORITY_KEY
            title = "Video Source Priority"
            entries = arrayOf(
                "Direct MP4 Links",
                "HLS Streams",
                "StreamWish",
                "Voe",
                "DoodStream",
                "StreamTape",
                "Ok.ru",
                "MixDrop"
            )
            entryValues = arrayOf("direct", "hls", "streamwish", "voe", "dood", "streamtape", "okru", "mixdrop")
            setDefaultValue(setOf("direct", "hls", "streamwish"))
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).apply()
                true
            }
        }.also(screen::addPreference)

        // Cache Management
        PreferenceCategory(screen.context).apply {
            title = "Cache & Storage"
            screen.addPreference(this)
        }

        Preference(screen.context).apply {
            key = PREF_CACHE_CLEAR_KEY
            title = "Clear Thumbnail Cache"
            summary = "Remove cached thumbnails and previews"
            setOnPreferenceClickListener {
                // Clear cache implementation
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            key = PREF_STORAGE_STATS_KEY
            title = "Storage Statistics"
            summary = "View cache usage and storage information"
            setOnPreferenceClickListener {
                // Show storage stats
                true
            }
        }.also(screen::addPreference)
    }

    // ==================== VIDEO SORTING ====================
    override fun List<Video>.sort(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val dataMode = preferences.getString(PREF_DATA_SAVING_KEY, PREF_DATA_SAVING_DEFAULT) ?: PREF_DATA_SAVING_DEFAULT
        val sourcePriority = preferences.getStringSet(PREF_SOURCE_PRIORITY_KEY, setOf("direct", "hls"))
        
        return sortedWith(compareByDescending<Video> { video ->
            // Source priority score
            val sourceScore = when {
                video.quality.contains("Direct") && "direct" in sourcePriority -> 100
                video.quality.contains("HLS") && "hls" in sourcePriority -> 90
                video.quality.contains("StreamWish") && "streamwish" in sourcePriority -> 80
                video.quality.contains("Voe") && "voe" in sourcePriority -> 70
                video.quality.contains("Dood") && "dood" in sourcePriority -> 60
                video.quality.contains("StreamTape") && "streamtape" in sourcePriority -> 50
                video.quality.contains("Ok.ru") && "okru" in sourcePriority -> 40
                video.quality.contains("MixDrop") && "mixdrop" in sourcePriority -> 30
                else -> 0
            }
            
            // Quality preference score
            val qualityScore = when {
                qualityPref == "auto" -> when (dataMode) {
                    "disabled" -> video.qualityValue
                    "wifi" -> if (isWifiConnected()) video.qualityValue else 0
                    "enabled" -> if (video.qualityValue <= 720) video.qualityValue else 0
                    "extreme" -> if (video.qualityValue <= 480) video.qualityValue else 0
                    "audio" -> if (video.quality.contains("audio")) 1000 else 0
                    else -> video.qualityValue
                }
                video.quality.contains(qualityPref) -> 1000 + video.qualityValue
                else -> video.qualityValue
            }
            
            sourceScore + qualityScore
        })
    }

    // ==================== UTILITY FUNCTIONS ====================
    private fun String.formatViews(): String {
        return try {
            val views = this.replace("[^0-9]".toRegex(), "").toLong()
            when {
                views >= 1_000_000_000 -> "${views / 1_000_000_000}B"
                views >= 1_000_000 -> "${views / 1_000_000}M"
                views >= 1_000 -> "${views / 1_000}K"
                else -> this
            }
        } catch (e: Exception) {
            this
        }
    }

    private fun String.formatRating(): String {
        return try {
            val rating = this.toFloat()
            String.format("%.1f/5", rating)
        } catch (e: Exception) {
            this
        }
    }

    private fun parseDateString(dateStr: String): Long {
        return try {
            // Try various date formats
            val formats = listOf(
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US),
                java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US),
                java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US),
                java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US)
            )
            
            for (format in formats) {
                try {
                    return format.parse(dateStr)?.time ?: continue
                } catch (e: Exception) {
                    continue
                }
            }
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private val Video.qualityValue: Int
        get() = Regex("""(\d+)""").find(this.quality)?.value?.toIntOrNull() ?: 0

    private fun isWifiConnected(): Boolean {
        // This would require Android context in a real implementation
        // For now, return true as most users will be on WiFi for streaming
        return true
    }

    // ==================== COMPANION OBJECT ====================
    companion object {
        // Preference Keys
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "auto"
        
        private const val PREF_SORT_KEY = "pref_sort"
        private const val PREF_SORT_DEFAULT = "most-viewed"
        
        private const val PREF_PREVIEW_KEY = "pref_preview"
        private const val PREF_PREVIEW_DEFAULT = true
        
        private const val PREF_AUTOPLAY_KEY = "pref_autoplay"
        private const val PREF_AUTOPLAY_DEFAULT = true
        
        private const val PREF_DATA_SAVING_KEY = "pref_data_saving"
        private const val PREF_DATA_SAVING_DEFAULT = "wifi"
        
        private const val PREF_SOURCE_PRIORITY_KEY = "pref_source_priority"
        private const val PREF_CACHE_CLEAR_KEY = "pref_cache_clear"
        private const val PREF_STORAGE_STATS_KEY = "pref_storage_stats"
    }
}
