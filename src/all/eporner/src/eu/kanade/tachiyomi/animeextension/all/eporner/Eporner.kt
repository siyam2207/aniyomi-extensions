package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.Context
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    // ==================== CLIENT CONFIGURATION ====================
    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cache-Control", "max-age=0")
                .build()
            chain.proceed(request)
        }
        .build()

    // ==================== PREFERENCES ====================
    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    // ==================== INTERNAL COMPONENTS ====================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ==================== POPULAR ANIME ====================
    override fun popularAnimeRequest(page: Int): Request {
        val sort = preferences.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT) ?: PREF_SORT_DEFAULT
        return GET("$baseUrl/$sort/$page/", headers)
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
        return when {
            query.isNotBlank() -> GET("$baseUrl/search/${query.slugify()}/$page/", headers)
            else -> {
                val url = buildFilterUrl(page, filters)
                GET(url, headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseVideoListing(response.asJsoup())
    }

    // ==================== VIDEO LISTING PARSER ====================
    private fun parseVideoListing(document: Document): AnimesPage {
        val elements = document.select("#videos .mb, .video-unit, div.video")
        
        val animeList = elements.mapNotNull { element ->
            try {
                SAnime.create().apply {
                    // URL
                    val link = element.selectFirst("a")
                    link?.let {
                        val href = it.attr("href")
                        setUrlWithoutDomain(if (href.startsWith("/")) "$baseUrl$href" else href)
                    }
                    
                    // Title
                    title = link?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: element.selectFirst(".title, h3")?.text()
                        ?: "Unknown Title"
                    
                    // Thumbnail with preview support
                    val enablePreviews = preferences.getBoolean(PREF_PREVIEW_KEY, PREF_PREVIEW_DEFAULT)
                    thumbnail_url = element.selectFirst("img")?.let { img ->
                        when {
                            enablePreviews -> {
                                // Try to get preview GIF first
                                img.attr("data-preview").takeIf { it.isNotBlank() }
                                    ?: img.attr("data-gif").takeIf { it.isNotBlank() }
                                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
                                    ?: img.attr("src")
                            }
                            else -> {
                                img.attr("data-src").takeIf { it.isNotBlank() }
                                    ?: img.attr("src")
                            }
                        }
                    }
                    
                    // Metadata
                    val duration = element.selectFirst(".duration, .time")?.text()
                    val views = element.selectFirst(".views")?.text()
                    val rating = element.selectFirst(".rating")?.text()
                    val isHD = element.selectFirst(".hd") != null
                    val isVR = element.selectFirst(".vr") != null
                    val is4K = element.selectFirst(".uhd") != null
                    
                    description = buildString {
                        duration?.let { append("⏱ $it\n") }
                        views?.let { append("👁 $it\n") }
                        rating?.let { append("⭐ $it\n") }
                        if (isHD) append("📺 HD\n")
                        if (isVR) append("🎥 VR\n")
                        if (is4K) append("📈 4K\n")
                    }.trim()
                    
                    // Extract video ID for previews
                    val videoId = link?.attr("href")?.substringAfterLast("/")?.substringBefore("?")
                    if (videoId != null && enablePreviews) {
                        // Store video ID for preview generation
                        thumbnail_url = "$baseUrl/video/$videoId/preview.jpg"
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
        
        val hasNextPage = document.select("a.next, .pagination a:contains(Next)").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ==================== ANIME DETAILS ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        
        return SAnime.create().apply {
            // Basic Info
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            
            // Metadata Extraction
            val infoItems = document.select(".info .item")
            val metadata = mutableMapOf<String, String>()
            infoItems.forEach { item ->
                val key = item.selectFirst(".label")?.text()?.trim()?.removeSuffix(":")
                val value = item.selectFirst(".value")?.text()?.trim()
                if (key != null && value != null) {
                    metadata[key] = value
                }
            }
            
            // Description Building
            description = buildString {
                // Main description
                document.selectFirst(".desc")?.text()?.let { append("$it\n\n") }
                
                // Metadata
                metadata.forEach { (key, value) ->
                    append("$key: $value\n")
                }
                
                // Actors
                val actors = document.select(".actors a").eachText()
                if (actors.isNotEmpty()) {
                    append("\n🎭 Actors: ${actors.joinToString(", ")}\n")
                }
                
                // Categories
                val categories = document.select(".categories a").eachText()
                if (categories.isNotEmpty()) {
                    append("📁 Categories: ${categories.joinToString(", ")}\n")
                }
                
                // Tags
                val tags = document.select(".tags a").eachText()
                if (tags.isNotEmpty()) {
                    append("🏷️ Tags: ${tags.joinToString(", ")}\n")
                }
                
                // Production Info
                val studio = document.selectFirst(".studio a")?.text()
                studio?.let { append("🎬 Studio: $it\n") }
                
                // Quality Info
                val qualities = mutableListOf<String>()
                if (document.selectFirst(".hd") != null) qualities.add("HD")
                if (document.selectFirst(".vr") != null) qualities.add("VR")
                if (document.selectFirst(".uhd") != null) qualities.add("4K")
                if (qualities.isNotEmpty()) {
                    append("📊 Quality: ${qualities.joinToString(", ")}\n")
                }
            }
            
            // Genre and Author
            val allCategories = document.select(".categories a").eachText()
            genre = allCategories.joinToString(", ")
            
            author = document.selectFirst(".studio a")?.text()
                ?: document.selectFirst(".production a")?.text()
            
            status = SAnime.COMPLETED
        }
    }

    // ==================== EPISODE LIST ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Eporner videos are single episodes
        return listOf(
            SEpisode.create().apply {
                name = "Full Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
                date_upload = parseUploadDate(response.asJsoup())
            },
        )
    }

    // ==================== VIDEO EXTRACTION ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        // Method 1: Extract from videoObj JavaScript
        val scriptData = document.select("script:containsData(videoObj)").firstOrNull()?.data()
        if (scriptData != null) {
            extractFromVideoObj(scriptData).let { videos.addAll(it) }
        }
        
        // Method 2: Extract from iframe embeds
        if (videos.isEmpty()) {
            document.select("iframe").mapNotNull { it.attr("src") }
                .parallelCatchingFlatMapBlocking { extractFromEmbed(it) }
                .let { videos.addAll(it) }
        }
        
        // Method 3: Extract from HTML5 video sources
        document.select("source").forEach { source ->
            val url = source.attr("src")
            if (url.isNotBlank()) {
                val quality = source.attr("label") ?: extractQualityFromUrl(url)
                videos.add(Video(url, quality, url))
            }
        }
        
        // Method 4: Try API endpoint
        if (videos.isEmpty()) {
            val videoId = document.location().substringAfterLast("/").substringBefore("?")
            extractFromApi(videoId).let { videos.addAll(it) }
        }
        
        return videos.distinctBy { it.videoUrl }.sortedWith(videoComparator)
    }

    private fun extractFromVideoObj(script: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Parse quality URLs from videoObj
        val qualityPattern = """["']?(\d+)p["']?\s*:\s*["']([^"']+)["']""".toRegex()
        qualityPattern.findAll(script).forEach { match ->
            val quality = "${match.groupValues[1]}p"
            val url = match.groupValues[2]
            if (url.isNotBlank() && !url.contains("null")) {
                videos.add(Video(url, "Direct - $quality", url))
            }
        }
        
        // Parse HLS manifest
        val hlsPattern = """hlsUrl\s*:\s*["']([^"']+)["']""".toRegex()
        hlsPattern.find(script)?.groupValues?.get(1)?.let { hlsUrl ->
            if (hlsUrl.isNotBlank()) {
                playlistUtils.extractFromHls(hlsUrl, baseUrl).let { videos.addAll(it) }
            }
        }
        
        return videos
    }

    private fun extractFromEmbed(embedUrl: String): List<Video> {
        return try {
            val response = client.newCall(GET(embedUrl, headers)).execute()
            val doc = response.asJsoup()
            
            val videos = mutableListOf<Video>()
            doc.select("source").forEach { source ->
                val url = source.attr("src")
                if (url.isNotBlank()) {
                    val quality = source.attr("label") ?: extractQualityFromUrl(url)
                    videos.add(Video(url, "Embed - $quality", url))
                }
            }
            
            // Check for packed JavaScript
            doc.select("script:containsData(function(p,a,c,k,e,d))").firstOrNull()?.data()?.let { packed ->
                Unpacker.unpack(packed)?.let { unpacked ->
                    val mp4Pattern = """(https?:[^"']+\.mp4[^"']*)""".toRegex()
                    mp4Pattern.findAll(unpacked).forEach { match ->
                        val url = match.value
                        videos.add(Video(url, "Unpacked - ${extractQualityFromUrl(url)}", url))
                    }
                }
            }
            
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractFromApi(videoId: String): List<Video> {
        return try {
            val url = "$baseUrl/api/video/$videoId"
            val response = client.newCall(GET(url, headers)).execute()
            val jsonString = response.body.string()
            
            val videos = mutableListOf<Video>()
            val qualityPattern = """["']?(\d+)p["']?\s*:\s*["']([^"']+)["']""".toRegex()
            qualityPattern.findAll(jsonString).forEach { match ->
                val quality = "${match.groupValues[1]}p"
                val url = match.groupValues[2]
                if (url.isNotBlank()) {
                    videos.add(Video(url, "API - $quality", url))
                }
            }
            
            videos
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== FILTER SYSTEM ====================
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            // Sort Filter
            SortFilter(),
            AnimeFilter.Separator(),
            
            // Category Filter Group
            AnimeFilter.Header("🎯 CATEGORIES"),
            CategoryFilter("Amateur", "amateur"),
            CategoryFilter("Anal", "anal"),
            CategoryFilter("Asian", "asian"),
            CategoryFilter("BBC", "bbc"),
            CategoryFilter("BBW", "bbw"),
            CategoryFilter("BDSM", "bdsm"),
            CategoryFilter("Big Tits", "big-tits"),
            CategoryFilter("Blonde", "blonde"),
            CategoryFilter("Blowjob", "blowjob"),
            CategoryFilter("Brunette", "brunette"),
            CategoryFilter("Bukkake", "bukkake"),
            CategoryFilter("College", "college"),
            CategoryFilter("Creampie", "creampie"),
            CategoryFilter("Cumshot", "cumshot"),
            CategoryFilter("Double Penetration", "double-penetration"),
            CategoryFilter("Ebony", "ebony"),
            CategoryFilter("Gangbang", "gangbang"),
            CategoryFilter("Hardcore", "hardcore"),
            CategoryFilter("Japanese", "japanese"),
            CategoryFilter("Latina", "latina"),
            CategoryFilter("Lesbian", "lesbian"),
            CategoryFilter("MILF", "milf"),
            CategoryFilter("Teen", "teen"),
            CategoryFilter("Threesome", "threesome"),
            CategoryFilter("VR", "vr"),
            CategoryFilter("4K", "4k"),
            AnimeFilter.Separator(),
            
            // Quality Filter
            AnimeFilter.Header("📊 QUALITY FILTERS"),
            QualityFilter(),
            AnimeFilter.Separator(),
            
            // Duration Filter
            AnimeFilter.Header("⏱ DURATION FILTERS"),
            DurationFilter(),
            AnimeFilter.Separator(),
            
            // Date Filter
            AnimeFilter.Header("📅 DATE FILTERS"),
            DateFilter(),
            AnimeFilter.Separator(),
            
            // Advanced Filters
            AnimeFilter.Header("⚙️ ADVANCED FILTERS"),
            HDOnlyFilter(),
            VRFilter(),
            PremiumFilter(),
            ActorFilter("Actor/Actress Name"),
            AnimeFilter.Separator(),
        )
    }

    // ==================== FILTER IMPLEMENTATIONS ====================
    private fun buildFilterUrl(page: Int, filters: AnimeFilterList): String {
        val url = "$baseUrl/filter/".toHttpUrl().newBuilder()
        
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("sort", filter.toUriPart())
                    }
                }
                is CategoryFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("category", filter.toUriPart())
                    }
                }
                is QualityFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("quality", filter.toUriPart())
                    }
                }
                is DurationFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("duration", filter.toUriPart())
                    }
                }
                is DateFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("date", filter.toUriPart())
                    }
                }
                is HDOnlyFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("hd", "1")
                    }
                }
                is VRFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("vr", "1")
                    }
                }
                is PremiumFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("premium", "1")
                    }
                }
                is ActorFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("actor", filter.state)
                    }
                }
            }
        }
        
        url.addQueryParameter("page", page.toString())
        return url.build().toString()
    }

    // ==================== PREFERENCE SCREEN ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Video Quality Preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "🎬 Preferred Video Quality"
            entries = arrayOf(
                "Auto (Best Available)",
                "4K (2160p)",
                "QHD (1440p)",
                "Full HD (1080p)",
                "HD (720p)",
                "SD (480p)",
                "Low (360p)",
            )
            entryValues = arrayOf("auto", "2160", "1440", "1080", "720", "480", "360")
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
            title = "📊 Default Sort Order"
            entries = arrayOf(
                "Most Viewed",
                "Top Rated",
                "Most Recent",
                "Longest",
                "Most Favorited",
            )
            entryValues = arrayOf("most-viewed", "top-rated", "latest", "longest", "most-favorited")
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
            title = "🎞️ Enable Video Previews"
            summary = "Show animated previews/GIFs for videos"
            setDefaultValue(PREF_PREVIEW_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        // Auto-play Preference
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTOPLAY_KEY
            title = "⏭️ Auto-play Next Video"
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
            title = "📱 Data Saving Mode"
            entries = arrayOf(
                "Disabled (Best Quality)",
                "Wi-Fi Only (Full Quality)",
                "Enabled (720p Max)",
                "Extreme (480p Max)",
            )
            entryValues = arrayOf("disabled", "wifi", "enabled", "extreme")
            setDefaultValue(PREF_DATA_SAVING_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        // Cache Management
        Preference(screen.context).apply {
            key = PREF_CACHE_CLEAR_KEY
            title = "🗑️ Clear Cache"
            summary = "Clear all cached thumbnails and data"
            setOnPreferenceClickListener {
                // Implementation would clear cache
                true
            }
        }.also(screen::addPreference)
    }

    // ==================== VIDEO SORTING ====================
    override fun List<Video>.sort(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val dataMode = preferences.getString(PREF_DATA_SAVING_KEY, PREF_DATA_SAVING_DEFAULT) ?: PREF_DATA_SAVING_DEFAULT
        
        return sortedWith(
            compareByDescending<Video> { video ->
                when (qualityPref) {
                    "auto" -> when (dataMode) {
                        "disabled" -> video.qualityValue
                        "wifi" -> if (isWifiConnected()) video.qualityValue else 0
                        "enabled" -> if (video.qualityValue <= 720) video.qualityValue else 0
                        "extreme" -> if (video.qualityValue <= 480) video.qualityValue else 0
                        else -> video.qualityValue
                    }
                    else -> if (video.quality.contains(qualityPref)) 1000 + video.qualityValue else video.qualityValue
                }
            }.thenByDescending { video ->
                video.qualityValue
            }
        )
    }

    // ==================== UTILITY FUNCTIONS ====================
    private fun parseUploadDate(document: Document): Long {
        return try {
            val dateText = document.selectFirst("span[itemprop='uploadDate']")?.attr("content")
                ?: document.selectFirst(".upload-date")?.text()
            
            if (dateText != null) {
                val formats = listOf(
                    SimpleDateFormat("yyyy-MM-dd", Locale.US),
                    SimpleDateFormat("MMM dd, yyyy", Locale.US),
                    SimpleDateFormat("dd MMM yyyy", Locale.US),
                )
                
                for (format in formats) {
                    try {
                        return format.parse(dateText)?.time ?: continue
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("2160") -> "2160p"
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

    private fun String.slugify(): String {
        return this.lowercase()
            .replace("[^a-z0-9\\s-]".toRegex(), "")
            .replace("\\s+".toRegex(), "-")
            .replace("-+".toRegex(), "-")
            .trim('-')
    }

    private fun isWifiConnected(): Boolean {
        // This would require Android context
        // For now, assume WiFi for better quality
        return true
    }

    private val Video.qualityValue: Int
        get() = Regex("""(\d+)""").find(this.quality)?.value?.toIntOrNull() ?: 0

    private val videoComparator = Comparator<Video> { v1, v2 ->
        v2.qualityValue.compareTo(v1.qualityValue)
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
        
        private const val PREF_CACHE_CLEAR_KEY = "pref_cache_clear"
    }
}

// ==================== FILTER CLASSES ====================

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class SortFilter : UriPartFilter(
    "Sort By",
    arrayOf(
        Pair("Most Viewed", "most-viewed"),
        Pair("Top Rated", "top-rated"),
        Pair("Most Recent", "latest"),
        Pair("Longest", "longest"),
        Pair("Most Favorited", "most-favorited"),
    ),
)

class CategoryFilter(
    name: String,
    private val slug: String,
) : AnimeFilter.CheckBox(name) {
    fun toUriPart() = slug
}

class QualityFilter : UriPartFilter(
    "Quality",
    arrayOf(
        Pair("Any", ""),
        Pair("4K", "4k"),
        Pair("HD", "hd"),
        Pair("SD", "sd"),
        Pair("Low", "low"),
    ),
)

class DurationFilter : UriPartFilter(
    "Duration",
    arrayOf(
        Pair("Any", ""),
        Pair("Short (<10min)", "short"),
        Pair("Medium (10-20min)", "medium"),
        Pair("Long (20-30min)", "long"),
        Pair("Very Long (30+min)", "very-long"),
    ),
)

class DateFilter : UriPartFilter(
    "Upload Date",
    arrayOf(
        Pair("Any Time", ""),
        Pair("Today", "today"),
        Pair("This Week", "week"),
        Pair("This Month", "month"),
        Pair("This Year", "year"),
    ),
)

class HDOnlyFilter : AnimeFilter.CheckBox("HD Only")
class VRFilter : AnimeFilter.CheckBox("VR Only")
class PremiumFilter : AnimeFilter.CheckBox("Premium Only")
class ActorFilter(name: String) : AnimeFilter.Text(name)

// Extension for HttpUrl
private fun String.toHttpUrl(): HttpUrl {
    return HttpUrl.parse(this) ?: throw IllegalArgumentException("Invalid URL: $this")
}
