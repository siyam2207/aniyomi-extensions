// Eporner.kt
package eu.kanade.tachiyomi.extension.all.eporner

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class Eporner : AnimeHttpSource() {
    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true
    
    // API Configuration
    private val apiBaseUrl = "$baseUrl/api/v2"
    private val jsonParser = Json { ignoreUnknownKeys = true }
    
    // Rate limiting to respect API
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            // Add delay to prevent overwhelming the API
            Thread.sleep(200)
            chain.proceed(request)
        }
        .build()
    
    // Popular videos (most-popular sorted)
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val apiUrl = "$apiBaseUrl/video/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "most-popular")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("thumbsize", "big")
            .build()
        
        val response = client.newCall(GET(apiUrl.toString())).await()
        val apiResponse = jsonParser.decodeFromString<ApiVideoListResponse>(response.body.string())
        
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                url = "/video/${video.id}"
                thumbnail_url = video.defaultThumb.src
                // Store additional data in initialized field for details parsing
                setInt(EpornerKeys.VIEWS, video.views)
                setString(EpornerKeys.RATING, video.rate)
                setString(EpornerKeys.DURATION, video.lengthMin)
                // Store all thumbnails for preview feature
                setString(EpornerKeys.THUMBNAILS, 
                    video.thumbs.joinToString("|||") { it.src })
                // Store keywords for actor extraction
                setString(EpornerKeys.KEYWORDS, video.keywords)
            }
        }
        
        return AnimesPage(animeList, apiResponse.page < apiResponse.totalPages)
    }
    
    // Latest videos (latest sorted)
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val apiUrl = "$apiBaseUrl/video/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("thumbsize", "medium")
            .build()
        
        val response = client.newCall(GET(apiUrl.toString())).await()
        val apiResponse = jsonParser.decodeFromString<ApiVideoListResponse>(response.body.string())
        
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                url = "/video/${video.id}"
                thumbnail_url = video.defaultThumb.src
                setString(EpornerKeys.ADDED_DATE, video.added)
            }
        }
        
        return AnimesPage(animeList, apiResponse.page < apiResponse.totalPages)
    }
    
    // Search with filters
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$apiBaseUrl/video/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "30")
        
        // Apply query if provided
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("query", query)
        } else {
            urlBuilder.addQueryParameter("query", "all")
        }
        
        // Apply filters from AnimeFilterList
        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    urlBuilder.addQueryParameter("order", filter.selected)
                }
                is CategoryFilter -> {
                    if (filter.selected.isNotBlank()) {
                        val currentQuery = urlBuilder.build().queryParameter("query") ?: "all"
                        urlBuilder.setQueryParameter("query", 
                            if (currentQuery == "all") filter.selected 
                            else "$currentQuery ${filter.selected}")
                    }
                }
                is QualityFilter -> {
                    urlBuilder.addQueryParameter("lq", 
                        if (filter.state) "0" else "1")
                }
                is ThumbSizeFilter -> {
                    urlBuilder.addQueryParameter("thumbsize", filter.selected)
                }
            }
        }
        
        val apiUrl = urlBuilder.build()
        val response = client.newCall(GET(apiUrl.toString())).await()
        
        if (!response.isSuccessful) {
            return AnimesPage(emptyList(), false)
        }
        
        val apiResponse = jsonParser.decodeFromString<ApiVideoListResponse>(response.body.string())
        
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                url = "/video/${video.id}"
                thumbnail_url = video.defaultThumb.src
                setString(EpornerKeys.KEYWORDS, video.keywords)
            }
        }
        
        return AnimesPage(animeList, apiResponse.page < apiResponse.totalPages)
    }
    
    // Video details parsing
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val videoId = anime.url.substringAfterLast("/")
        val apiUrl = "$apiBaseUrl/video/id/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("id", videoId)
            .addQueryParameter("thumbsize", "big")
            .build()
        
        val response = client.newCall(GET(apiUrl.toString())).await()
        val apiResponse = jsonParser.decodeFromString<ApiVideoDetailResponse>(response.body.string())
        val video = apiResponse.video
        
        return anime.apply {
            title = video.title
            description = buildString {
                append("Duration: ${video.length_min}\n")
                append("Views: ${video.views}\n")
                append("Rating: ${video.rate}/5\n")
                append("Added: ${video.added}\n\n")
                append("Keywords:\n${video.keywords.replace(", ", "\n")}")
            }
            thumbnail_url = video.defaultThumb.src
            // Store all data for episode parsing
            setString(EpornerKeys.VIDEO_ID, video.id)
            setString(EpornerKeys.VIDEO_URL, video.url)
            setString(EpornerKeys.EMBED_URL, video.embed)
            setString(EpornerKeys.THUMBNAILS, 
                video.thumbs.joinToString("|||") { it.src })
            setInt(EpornerKeys.VIEWS, video.views)
            setString(EpornerKeys.RATING, video.rate)
            setString(EpornerKeys.DURATION, video.lengthMin)
            setString(EpornerKeys.KEYWORDS, video.keywords)
            genre = video.keywords.split(", ").joinToString(", ")
        }
    }
    
    // Episode list (videos are single episodes)
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val videoId = anime.getString(EpornerKeys.VIDEO_ID) ?: 
                     anime.url.substringAfterLast("/")
        
        return listOf(SEpisode.create().apply {
            name = anime.title
            episode_number = 1F
            date_upload = parseDate(anime.getString(EpornerKeys.ADDED_DATE) ?: "")
            url = "/watch/$videoId"
            scanlator = "Eporner"
        })
    }
    
    // Video URL extraction (most complex part)
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoId = episode.url.substringAfterLast("/")
        
        // First try to get from embed page
        val embedUrl = "https://www.eporner.com/embed/$videoId/"
        val embedResponse = client.newCall(GET(embedUrl)).await()
        val embedDocument = embedResponse.asJsoup()
        
        // Look for video sources in embed page
        val videoSources = embedDocument.select("source[src]").mapNotNull { element ->
            val src = element.attr("src")
            if (src.isNotBlank() && (src.endsWith(".mp4") || src.contains("video"))) {
                Video(src, "Direct", src)
            } else null
        }
        
        if (videoSources.isNotEmpty()) {
            return videoSources
        }
        
        // Fallback to API for video information
        try {
            val apiUrl = "$apiBaseUrl/video/id/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("id", videoId)
                .build()
            
            val apiResponse = client.newCall(GET(apiUrl.toString())).await()
            val videoDetail = jsonParser.decodeFromString<ApiVideoDetailResponse>(
                apiResponse.body.string())
            
            // Construct potential video URLs based on common patterns
            val potentialUrls = listOf(
                "https://www.eporner.com/video/$videoId/download/",
                videoDetail.video.url.replace("/hd-porn/", "/download/")
            )
            
            return potentialUrls.mapIndexed { index, url ->
                Video(url, "Source ${index + 1}", url)
            }
        } catch (e: Exception) {
            throw Exception("Could not extract video URL: ${e.message}")
        }
    }
    
    // Category implementation
    suspend fun getCategoryAnime(category: String, page: Int): AnimesPage {
        val apiUrl = "$apiBaseUrl/video/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", category)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "50")
            .addQueryParameter("order", "top-weekly")
            .build()
        
        val response = client.newCall(GET(apiUrl.toString())).await()
        val apiResponse = jsonParser.decodeFromString<ApiVideoListResponse>(response.body.string())
        
        val animeList = apiResponse.videos.map { video ->
            SAnime.create().apply {
                title = video.title
                url = "/video/${video.id}"
                thumbnail_url = video.defaultThumb.src
                genre = category.replace("-", " ").capitalize()
            }
        }
        
        return AnimesPage(animeList, apiResponse.page < apiResponse.totalPages)
    }
    
    // Actor/Pornstar implementation
    suspend fun getActorList(page: Int): List<Actor> {
        val url = "$baseUrl/pornstar-list/${if (page > 1) "$page/" else ""}"
        val response = client.newCall(GET(url)).await()
        val document = response.asJsoup()
        
        return document.select(".mbprofile").mapNotNull { element ->
            val nameElement = element.select("a[href^=/pornstar/]").first()
            val thumbElement = element.select("img").first()
            
            if (nameElement != null && thumbElement != null) {
                Actor(
                    name = nameElement.text(),
                    url = nameElement.attr("href"),
                    thumbnail = thumbElement.attr("src"),
                    videoCount = element.select(".videos-count").text()
                        .removePrefix("(").removeSuffix(")").toIntOrNull() ?: 0
                )
            } else null
        }
    }
    
    suspend fun getActorVideos(actorUrl: String, page: Int): AnimesPage {
        val actorName = actorUrl.substringAfterLast("/").removeSuffix("/")
            .replace("-", " ")
        
        return getSearchAnime(page, actorName, AnimeFilterList())
    }
    
    // Helper functions
    private fun parseDate(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Implemented in getSearchAnime directly
        throw UnsupportedOperationException()
    }
    
    override fun searchAnimeParse(response: Response): AnimesPage {
        // Implemented in getSearchAnime directly
        throw UnsupportedOperationException()
    }
    
    override fun popularAnimeRequest(page: Int): Request {
        // Implemented in getPopularAnime directly
        throw UnsupportedOperationException()
    }
    
    override fun popularAnimeParse(response: Response): AnimesPage {
        // Implemented in getPopularAnime directly
        throw UnsupportedOperationException()
    }
    
    override fun latestUpdatesRequest(page: Int): Request {
        // Implemented in getLatestUpdates directly
        throw UnsupportedOperationException()
    }
    
    override fun latestUpdatesParse(response: Response): AnimesPage {
        // Implemented in getLatestUpdates directly
        throw UnsupportedOperationException()
    }
    
    override fun animeDetailsParse(response: Response): SAnime {
        // Implemented in getAnimeDetails directly
        throw UnsupportedOperationException()
    }
    
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Implemented in getEpisodeList directly
        throw UnsupportedOperationException()
    }
    
    override fun videoListParse(response: Response): List<Video> {
        // Implemented in getVideoList directly
        throw UnsupportedOperationException()
    }
}

// Keys for storing additional data in SAnime
object EpornerKeys {
    const val VIDEO_ID = "eporner_video_id"
    const val VIDEO_URL = "eporner_video_url"
    const val EMBED_URL = "eporner_embed_url"
    const val THUMBNAILS = "eporner_thumbnails"
    const val VIEWS = "eporner_views"
    const val RATING = "eporner_rating"
    const val DURATION = "eporner_duration"
    const val KEYWORDS = "eporner_keywords"
    const val ADDED_DATE = "eporner_added_date"
}

// Data class for actors
data class Actor(
    val name: String,
    val url: String,
    val thumbnail: String,
    val videoCount: Int
)

// Factory for multiple sections
class EpornerFactory : AnimeSourceFactory {
    override fun createSources(context: Context): List<AnimeSource> = listOf(
        Eporner(),
        EpornerCategories(),
        EpornerActors(),
        EpornerChannels()
    )
}

// Category-specific source
class EpornerCategories : Eporner() {
    override val name = "Eporner (Categories)"
    
    private val categories = listOf(
        "anal", "teen", "milf", "big-tits", "amateur", "hardcore",
        "blowjob", "creampie", "asian", "blonde", "latina", "bbc",
        "lesbian", "threesome", "pov", "orgy", "rough", "fetish"
    )
    
    // Override to show categories instead of direct videos
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val categoryList = categories.map { category ->
            SAnime.create().apply {
                title = category.replace("-", " ").capitalize()
                url = "/category/$category/"
                thumbnail_url = "$baseUrl/thumbs/categories/${category}.jpg"
                genre = "Category"
            }
        }
        return AnimesPage(categoryList, false)
    }
}

// Actor-specific source
class EpornerActors : Eporner() {
    override val name = "Eporner (Actors)"
    
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val actors = getActorList(page)
        val actorList = actors.map { actor ->
            SAnime.create().apply {
                title = actor.name
                url = actor.url
                thumbnail_url = actor.thumbnail
                description = "${actor.videoCount} videos"
                genre = "Pornstar"
            }
        }
        return AnimesPage(actorList, actors.isNotEmpty())
    }
}

// Channel-specific source (requires HTML parsing)
class EpornerChannels : Eporner() {
    override val name = "Eporner (Channels)"
    override val baseUrl = "https://www.eporner.com"
    
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/channels/${if (page > 1) "$page/" else ""}"
        val response = client.newCall(GET(url)).await()
        val document = response.asJsoup()
        
        val channels = document.select(".channel-item").mapNotNull { element ->
            val titleElement = element.select(".channel-title a").first()
            val thumbElement = element.select("img").first()
            
            if (titleElement != null && thumbElement != null) {
                SAnime.create().apply {
                    title = titleElement.text()
                    url = titleElement.attr("href")
                    thumbnail_url = thumbElement.attr("src")
                    genre = "Channel"
                }
            } else null
        }
        
        val hasNext = document.select(".pagination .next").isNotEmpty()
        return AnimesPage(channels, hasNext)
    }
}
