package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceManager
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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
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
        val order = preferences?.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT) ?: PREF_SORT_DEFAULT
        val url = "$apiUrl/video/search/?query=all&page=$page&order=$order&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val body = response.body.string()
            val apiResponse = json.decodeFromString<ApiSearchResponse>(body)
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.totalPages
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
                else -> {}
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
        // anime.url is already the embed URL (stored from API)
        return GET(anime.url, headers).newBuilder().tag(SAnime::class.java to anime).build()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        // Get the existing anime from the request tag
        val tagPair = response.request.tag() as? Pair<*, *>
        val anime = if (tagPair?.first == SAnime::class.java) {
            tagPair.second as? SAnime ?: SAnime.create()
        } else {
            SAnime.create()
        }

        // Parse the video page to get actors and uploader
        val document = response.asJsoup()

        return anime.apply {
            // Title already set from API, but fallback to page if missing
            if (title.isNullOrBlank()) {
                title = extractTitle(document) ?: "Unknown"
            }

            // Thumbnail from API already set, but fallback
            if (thumbnail_url.isNullOrBlank()) {
                thumbnail_url = extractThumbnail(document)
            }

            // Description from API already set (contains tags/views), but we add actors/uploader
            description = buildEnhancedDescription(document, this)

            // Set artist field to actors (for searchability)
            artist = extractActors(document)?.joinToString(", ")

            status = SAnime.COMPLETED
        }
    }

    // ==================== Episode List ====================
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Single episode – the video itself
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString() // embed URL
            },
        )
    }

    // ==================== Video List ====================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        return try {
            val html = response.body.string()
            val embedUrl = response.request.url.toString()

            val masterUrl = findMasterUrl(html) ?: return emptyList()

            // Manually fetch and parse master playlist
            val masterPlaylist = client.newCall(GET(masterUrl, videoHeaders(embedUrl))).execute().body.string()
            parseMasterPlaylist(masterPlaylist, masterUrl, embedUrl)
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error", e)
            emptyList()
        }
    }

    private fun parseMasterPlaylist(playlist: String, masterUrl: String, embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val baseUrl = masterUrl.substringBeforeLast('/') + '/'

        // Regex to extract resolution and URL from EXT-X-STREAM-INF lines
        val regex = Regex("""#EXT-X-STREAM-INF:.*RESOLUTION=(\d+x\d+).*\n(.*\.m3u8.*)""")
        val matches = regex.findAll(playlist)

        for (match in matches) {
            val resolution = match.groupValues[1]
            val url = match.groupValues[2].trim()
            val quality = resolution.substringBefore('x') + "p"
            val fullUrl = if (url.startsWith("http")) url else baseUrl + url
            videos.add(Video(fullUrl, quality, fullUrl, headers = videoHeaders(embedUrl)))
        }

        // Fallback: if no variants found, just use the master URL itself
        if (videos.isEmpty()) {
            videos.add(Video(masterUrl, "HLS", masterUrl, headers = videoHeaders(embedUrl)))
        }

        return videos.sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
    }

    // ==================== Helper Methods ====================
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

    private fun findMasterUrl(html: String): String? {
        val regexes = listOf(
            Regex("""https://[^"' ]+master\.m3u8[^"' ]*"""),
            Regex("""var\s+master\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""masterUrl\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""hlsUrl\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
        )
        for (regex in regexes) {
            val match = regex.find(html) ?: continue
            val url = match.groups[1]?.value ?: match.value
            return url
        }
        return null
    }

    // ----- Video page extraction for actors and uploader -----
    private fun extractTitle(document: Document): String? {
        return document.selectFirst("h1")?.text()
    }

    private fun extractThumbnail(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
    }

    private fun extractActors(document: Document): List<String>? {
        // Try JSON-LD first
        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        if (jsonLd != null) {
            try {
                val obj = json.parseToJsonElement(jsonLd).jsonObject
                val actorElement = obj["actor"]
                if (actorElement is JsonArray) {
                    return actorElement.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        // Fallback to HTML tags
        val actorElements = document.select("li.vit-pornstar a")
        if (actorElements.isNotEmpty()) {
            return actorElements.mapNotNull { it.text().ifBlank { null } }
        }
        return null
    }

    private fun extractUploader(document: Document): String? {
        return document.selectFirst("li.vit-uploader a")?.text()
    }

    private fun buildEnhancedDescription(document: Document, anime: SAnime): String {
        val parts = mutableListOf<String>()

        // Start with existing description (from API) which contains tags and stats
        val existingDescription = anime.description
        if (!existingDescription.isNullOrBlank()) {
            parts.add(existingDescription)
        }

        // Add actors
        val actors = extractActors(document)
        if (!actors.isNullOrEmpty()) {
            parts.add("\n\nStarring: ${actors.joinToString(", ")}")
        }

        // Add uploader
        val uploader = extractUploader(document)
        if (!uploader.isNullOrBlank()) {
            parts.add("\n\nUploader: $uploader")
        }

        return parts.joinToString("")
    }

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

    // ==================== Settings ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences = PreferenceManager.getDefaultSharedPreferences(screen.context)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)

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

        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default sort order"
            entries = SORT_LIST
            entryValues = SORT_LIST
            setDefaultValue(PREF_SORT_DEFAULT)

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
        @SerialName("total_pages") val totalPages: Int,
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("views") val views: Long,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown"
            this.url = this@ApiVideo.embed // store embed URL directly
            this.thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }
            this.genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }
            this.description = "Views: ${this@ApiVideo.views}\n\nTags: ${this@ApiVideo.keywords}"
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
