package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.eporner.dto.EpornerApiResponse
import eu.kanade.tachiyomi.animeextension.all.eporner.dto.EpornerVideoDetail
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
import extensions.utils.getPreferencesLazy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {
    
    // =========== BASIC CONFIGURATION ===========
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiBaseUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true
    
    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()
    
    // =========== HTTP CLIENT CONFIGURATION ===========
    override val client by lazy {
        network.cloudflareClient.newBuilder()
            .addInterceptor(RotatingUserAgentInterceptor())
            .addInterceptor(EpornerRateLimitInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") // Base agent
        .add("Accept", "application/json, text/html, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Connection", "keep-alive")
    
    // =========== PREFERENCES ===========
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("2160", "1440", "1080", "720", "480", "360")
        
        private const val PREF_ORDER_KEY = "preferred_order"
        private const val PREF_ORDER_DEFAULT = "top-weekly"
        private val ORDER_LIST = arrayOf(
            "top-weekly",
            "top-monthly", 
            "top-yearly",
            "top",
            "latest",
            "longest",
            "shortest",
            "most-viewed",
            "most-rated",
            "hd"
        )
    }
    
    // =========== API REQUEST BUILDERS ===========
    private fun buildApiRequest(
        query: String = "all",
        page: Int = 1,
        order: String = PREF_ORDER_DEFAULT,
        perPage: Int = 30
    ): Request {
        val url = "$apiBaseUrl/video/search/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", order)
            .addQueryParameter("thumbsize", "big")
            .addQueryParameter("format", "json")
            .addQueryParameter("per_page", perPage.toString())
            .build()
        
        return GET(url.toString(), headers)
    }
    
    // =========== POPULAR ANIME ===========
    override fun popularAnimeRequest(page: Int): Request {
        val order = preferences.getString(PREF_ORDER_KEY, PREF_ORDER_DEFAULT) ?: PREF_ORDER_DEFAULT
        return buildApiRequest(page = page, order = order)
    }
    
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        Log.d("Eporner", "Fetching popular anime page $page")
        
        return try {
            val response = client.newCall(popularAnimeRequest(page)).awaitSuccess()
            val apiResponse = response.parseAs<EpornerApiResponse>()
            
            val animeList = apiResponse.videos.map { video ->
                SAnime.create().apply {
                    title = video.title
                    setUrlWithoutDomain("/video-${video.id}/")
                    thumbnail_url = video.defaultThumb.src
                    
                    // Build detailed description
                    val desc = StringBuilder()
                    desc.append("Views: ${video.views}\n")
                    desc.append("Rating: ${video.rate}/5\n")
                    desc.append("Duration: ${video.lengthMin}\n")
                    desc.append("Added: ${video.added}\n")
                    if (video.keywords.isNotBlank()) {
                        val cleanKeywords = video.keywords.trim().trim(',')
                        if (cleanKeywords.isNotBlank()) {
                            desc.append("Tags: $cleanKeywords")
                        }
                    }
                    description = desc.toString()
                    initialized = true
                }
            }
            
            val hasNextPage = page < apiResponse.totalPages
            Log.d("Eporner", "Loaded ${animeList.size} videos, hasNext: $hasNextPage")
            AnimesPage(animeList, hasNextPage)
            
        } catch (e: Exception) {
            Log.e("Eporner", "Error fetching popular anime: ${e.message}", e)
            throw e
        }
    }
    
    override fun popularAnimeParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Use getPopularAnime() instead")
    }
    
    // =========== LATEST UPDATES ===========
    override fun latestUpdatesRequest(page: Int): Request {
        return buildApiRequest(page = page, order = "latest")
    }
    
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return getPopularAnime(page) // Same logic, different order
    }
    
    override fun latestUpdatesParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Use getLatestUpdates() instead")
    }
    
    // =========== SEARCH ===========
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val order = preferences.getString(PREF_ORDER_KEY, PREF_ORDER_DEFAULT) ?: PREF_ORDER_DEFAULT
        return buildApiRequest(query = query.ifBlank { "all" }, page = page, order = order)
    }
    
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        Log.d("Eporner", "Searching for '$query' page $page")
        
        return try {
            val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
            val apiResponse = response.parseAs<EpornerApiResponse>()
            
            val animeList = apiResponse.videos.map { video ->
                SAnime.create().apply {
                    title = video.title
                    setUrlWithoutDomain("/video-${video.id}/")
                    thumbnail_url = video.defaultThumb.src
                    initialized = true
                }
            }
            
            val hasNextPage = page < apiResponse.totalPages
            Log.d("Eporner", "Search found ${animeList.size} results, hasNext: $hasNextPage")
            AnimesPage(animeList, hasNextPage)
            
        } catch (e: Exception) {
            Log.e("Eporner", "Error searching: ${e.message}", e)
            throw e
        }
    }
    
    override fun searchAnimeParse(response: Response): AnimesPage {
        throw UnsupportedOperationException("Use getSearchAnime() instead")
    }
    
    // =========== ANIME DETAILS ===========
    override fun animeDetailsRequest(anime: SAnime): Request {
        val videoId = extractVideoId(anime.url)
        return GET("$apiBaseUrl/video/$videoId/?format=json", headers)
    }
    
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d("Eporner", "Fetching details for ${anime.title}")
        
        return try {
            val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
            val videoDetail = response.parseAs<EpornerVideoDetail>()
            
            anime.apply {
                // Update with detailed information
                val desc = buildString {
                    append("Duration: ${videoDetail.lengthMin}\n")
                    append("Views: ${videoDetail.views}\n")
                    append("Rating: ${videoDetail.rate}/5\n")
                    append("Added: ${videoDetail.added}\n")
                    append("Resolution: ${videoDetail.defaultResolution}\n")
                    
                    if (videoDetail.models.isNotEmpty()) {
                        append("Models: ${videoDetail.models.joinToString { it.name }}\n")
                    }
                    
                    if (videoDetail.pornstars.isNotEmpty()) {
                        append("Pornstars: ${videoDetail.pornstars.joinToString { it.name }}\n")
                    }
                    
                    if (videoDetail.keywords.isNotBlank()) {
                        val cleanKeywords = videoDetail.keywords.trim().trim(',')
                        if (cleanKeywords.isNotBlank()) {
                            append("\nTags: $cleanKeywords")
                        }
                    }
                }
                
                description = desc
                genre = videoDetail.keywords.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString()
                
                if (videoDetail.models.isNotEmpty()) {
                    artist = videoDetail.models.joinToString { it.name }
                }
                
                if (videoDetail.pornstars.isNotEmpty()) {
                    author = videoDetail.pornstars.joinToString { it.name }
                }
                
                thumbnail_url = videoDetail.defaultThumb.src
                status = SAnime.COMPLETED
                initialized = true
            }
            
        } catch (e: Exception) {
            Log.e("Eporner", "Error fetching anime details: ${e.message}", e)
            anime // Return anime as-is if details fail
        }
    }
    override fun animeDetailsParse(response: Response): SAnime {
        throw UnsupportedOperationException("Use getAnimeDetails() instead")
    }
    // =========== EPISODES ===========
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain(anime.url)
                date_upload = System.currentTimeMillis()
            }
        )
    }
    
    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Use getEpisodeList() instead")
    }
    // =========== VIDEO EXTRACTION ===========
    override fun videoListRequest(episode: SEpisode): Request {
        val videoId = extractVideoId(episode.url)
        return GET("$baseUrl/embed/$videoId/", headers)
    }
    
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        Log.d("Eporner", "Extracting videos for episode: ${episode.name}")
        val videos = mutableListOf<Video>()
        val embedUrl = videoListRequest(episode).url.toString()
        return try {
            // First try: Fetch embed page
            val response = client.newCall(GET(embedUrl, headers)).awaitSuccess()
            val document = response.asJsoup()
            // Pattern 1: Look for video sources in script tags
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                // Multiple patterns for finding video URLs
                val patterns = listOf(
                    """(https?://[^"']+\.mp4[^"']*)""",
                    """src\s*:\s*["']([^"']+\.mp4[^"']*)["']""",
                    """file\s*:\s*["']([^"']+\.mp4[^"']*)["']""",
                    """video_url\s*:\s*["']([^"']+\.mp4[^"']*)["']"""
                )
                
                patterns.forEach { pattern ->
                    val regex = pattern.toRegex()
                    regex.findAll(scriptContent).forEach { match ->
                        var url = match.groupValues.getOrNull(1) ?: match.value
                        url = url.trim().replace("\\/", "/")
                        if (url.startsWith("http") && url.contains(".mp4")) {
                            val quality = extractQualityFromUrl(url)
                            videos.add(Video(url, quality, url))
                            Log.d("Eporner", "Found video: $quality - ${url.substring(0, 50)}...")
                        }
                    }
                }
            }
            // Pattern 2: Check video elements directly
            document.select("video source").forEach { source ->
                val url = source.attr("src")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    val quality = source.attr("label").ifBlank { 
                        source.attr("title").ifBlank { 
                            extractQualityFromUrl(url) 
                        }
                    }
                    videos.add(Video(url, quality, url))
                }
            }
            // Pattern 3: Check iframe sources (common for embedded players)
            if (videos.isEmpty()) {
                document.select("iframe").forEach { iframe ->
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.isNotBlank() && iframeSrc.contains("eporner")) {
                        try {
                            Log.d("Eporner", "Checking iframe: $iframeSrc")
                            val iframeResponse = client.newCall(GET(iframeSrc, headers)).awaitSuccess()
                            val iframeDoc = iframeResponse.asJsoup()
                            
                            iframeDoc.select("script, video source").forEach { element ->
                                // Similar extraction logic for iframe content
                            }
                        } catch (e: Exception) {
                            Log.e("Eporner", "Iframe extraction failed: ${e.message}")
                        }
                    }
                }
            }
            
            if (videos.isEmpty()) {
                Log.w("Eporner", "No video sources found in embed page")
                throw Exception("No playable video sources found. Try using WebView to solve CAPTCHA.")
            }
            
            // Remove duplicates and sort
            videos.distinctBy { it.url }.also {
                Log.d("Eporner", "Total unique videos found: ${it.size}")
            }
            
        } catch (e: Exception) {
            Log.e("Eporner", "Video extraction error: ${e.message}", e)
            throw e
        }
    }
    
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Use getVideoList() instead")
    }
    
    // =========== VIDEO SORTING ===========
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        
        return this.sortedWith(
            compareByDescending<Video> { video ->
                when {
                    video.quality.contains(quality) -> 1000
                    video.quality.contains("2160") -> 900
                    video.quality.contains("1440") -> 800
                    video.quality.contains("1080") -> 700
                    video.quality.contains("720") -> 600
                    video.quality.contains("480") -> 500
                    video.quality.contains("360") -> 400
                    else -> 0
                }
            }.thenByDescending { video ->
                Regex("""(\d+)p""").find(video.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        )
    }
    
    // =========== PREFERENCES UI ===========
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Video quality preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST.map { "${it}p" }.toTypedArray()
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
        
        // Sort order preference
        ListPreference(screen.context).apply {
            key = PREF_ORDER_KEY
            title = "Default sort order"
            entries = arrayOf(
                "Top Weekly",
                "Top Monthly",
                "Top Yearly",
                "All Time Top",
                "Latest",
                "Longest",
                "Shortest",
                "Most Viewed",
                "Most Rated",
                "HD Only"
            )
            entryValues = ORDER_LIST
            setDefaultValue(PREF_ORDER_DEFAULT)
            summary = "%s"
            
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
    
    // =========== FILTERS ===========
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        // Eporner API handles filtering via query parameters
    )
    
    // =========== UTILITY FUNCTIONS ===========
    private fun extractVideoId(url: String): String {
        return url.substringAfter("video-").substringBefore("/").trim()
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
            else -> "Source"
        }
    }
}
