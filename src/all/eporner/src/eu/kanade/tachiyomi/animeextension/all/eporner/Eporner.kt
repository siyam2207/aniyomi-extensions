package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import android.preference.PreferenceManager
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
            val animeList = apiResponse.videos.map { video ->
                SAnime.create().apply {
                    title = video.title.takeIf { it.isNotBlank() } ?: "Unknown"
                    // Store relative URL by stripping baseUrl if present
                    url = if (video.embed.startsWith(baseUrl)) {
                        video.embed.substring(baseUrl.length)
                    } else {
                        video.embed
                    }
                    thumbnail_url = video.defaultThumb.src.takeIf { it.isNotBlank() }
                    genre = video.keywords.takeIf { it.isNotBlank() }
                    description = "Views: ${video.views}\n\nTags: ${video.keywords}"
                    status = SAnime.COMPLETED
                }
            }
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
        // Prepend baseUrl because anime.url is now relative
        val fullUrl = baseUrl + anime.url
        return GET(fullUrl, headers).newBuilder().tag(SAnime::class.java to anime).build()
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
        // Convert the full request URL to relative before storing
        val relativeUrl = toRelativeUrl(response.request.url.toString())
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = relativeUrl
            },
        )
    }

    // ==================== Video List ====================
    override fun videoListRequest(episode: SEpisode): Request {
        // Prepend baseUrl because episode.url is now relative
        val fullUrl = baseUrl + episode.url
        return GET(fullUrl, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        return try {
            val html = response.body.string()
            val embedUrl = response.request.url.toString()

            // Extract vid and hash from embed page
            val vid = regexFind(html, """EP\.video\.player\.vid\s*=\s*['"]([^'"]+)['"]""")
                ?: return emptyList()
            val embedHash = regexFind(html, """EP\.video\.player\.hash\s*=\s*['"]([^'"]+)['"]""")
                ?: return emptyList()

            // Transform hash to XHR hash
            val xhrHash = transformHash(embedHash)

            // Build XHR request URL with parameters
            val xhrUrl = "https://www.eporner.com/xhr/video/$vid".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("hash", xhrHash)
                .addQueryParameter("domain", "www.eporner.com")
                .addQueryParameter("pixelRatio", "1")
                .addQueryParameter("playerWidth", "0")
                .addQueryParameter("playerHeight", "0")
                .addQueryParameter("fallback", "false")
                .addQueryParameter("embed", "true")
                .addQueryParameter("supportedFormats", "hls,dash,vp9,av1,mp4")
                .addQueryParameter("_", System.currentTimeMillis().toString())
                .build()
                .toString()

            val xhrRequest = GET(xhrUrl, headers = videoHeaders(embedUrl))
            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body.string()
            val data = json.decodeFromString<XhrResponse>(xhrBody)

            if (!data.available) {
                return emptyList()
            }

            val videos = mutableListOf<Video>()

            // Add MP4 sources
            data.sources.mp4?.forEach { (quality, info) ->
                videos.add(Video(info.src, quality, info.src, headers = videoHeaders(embedUrl)))
            }

            // Add HLS streams using PlaylistUtils (individual qualities)
            data.sources.hls?.auto?.src?.let { hlsUrl ->
                val playlistUtils = PlaylistUtils(client, headers)
                val hlsVideos = playlistUtils.extractFromHls(
                    playlistUrl = hlsUrl,
                    referer = embedUrl,
                    masterHeaders = videoHeaders(embedUrl),
                    videoHeaders = videoHeaders(embedUrl),
                    videoNameGen = { quality -> quality },
                )
                videos.addAll(hlsVideos)
            }

            videos.sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
        } catch (e: Exception) {
            Log.e(tag, "Video list parse error", e)
            emptyList()
        }
    }

    private fun regexFind(text: String, pattern: String): String? {
        return Regex(pattern).find(text)?.groupValues?.get(1)
    }

    private fun transformHash(embedHash: String): String {
        // Split into 8-char chunks, convert hex to long, then to base36
        return embedHash.chunked(8).joinToString("") { chunk ->
            val num = chunk.toLong(16)
            toBase36(num)
        }
    }

    private fun toBase36(num: Long): String {
        if (num == 0L) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var n = num
        var result = ""
        while (n > 0) {
            val rem = (n % 36).toInt()
            result = digits[rem] + result
            n /= 36
        }
        return result
    }

    // ==================== Helper Methods ====================
    private fun toRelativeUrl(fullUrl: String): String {
        return if (fullUrl.startsWith(baseUrl)) {
            fullUrl.substring(baseUrl.length)
        } else {
            fullUrl // assume already relative
        }
    }

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
    )

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )

    // ==================== XHR Response Data Classes ====================
    @Serializable
    private data class XhrResponse(
        val available: Boolean,
        val sources: XhrSources,
        val message: String? = null,
    )

    @Serializable
    private data class XhrSources(
        val hls: XhrHls? = null,
        val mp4: Map<String, XhrMp4>? = null,
    )

    @Serializable
    private data class XhrHls(
        val auto: XhrHlsAuto? = null,
    )

    @Serializable
    private data class XhrHlsAuto(
        val src: String,
        val srcFallback: String? = null,
    )

    @Serializable
    private data class XhrMp4(
        val src: String,
    )
}
