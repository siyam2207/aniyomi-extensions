package eu.kanade.tachiyomi.animeextension.all.eporner

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
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val apiUrl = "https://www.eporner.com/api/v2"
    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()
    private val tag = "Eporner"

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("Accept", "application/json, text/html")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Origin", baseUrl)
            .add("Referer", baseUrl)
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
    }

    private fun videoHeaders(): Headers {
        return headers.newBuilder()
            .set("Origin", baseUrl)
            .set("Referer", baseUrl)
            .build()
    }

    // ============================== POPULAR ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val url =
            "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val data = json.decodeFromString<ApiSearchResponse>(body)
        val animeList = data.videos.map { it.toSAnime() }
        val hasNextPage = data.page < data.totalPages
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== LATEST ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url =
            "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ============================== SEARCH ==============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Request {
        val encodedQuery =
            if (query.isBlank()) "all" else URLEncoder.encode(query, "UTF-8")

        var category = "all"
        var duration = "0"
        var quality = "0"

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> category = filter.toUriPart()
                is DurationFilter -> duration = filter.toUriPart()
                is QualityFilter -> quality = filter.toUriPart()
                else -> Unit
            }
        }

        val url =
            "$apiUrl/video/search/?query=$encodedQuery&page=$page&categories=$category" +
                "&duration=$duration&quality=$quality&thumbsize=big&format=json"

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ============================== DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id =
            Regex("""video-([^/]+)""")
                .find(anime.url)
                ?.groupValues
                ?.get(1)
                ?: error("Unable to extract video id")

        val url = "$apiUrl/video/id/?id=$id&format=json"
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return try {
            val body = response.body.string()
            val data = json.decodeFromString<ApiVideoDetailResponse>(body)
            data.toSAnime()
        } catch (e: Exception) {
            Log.e(tag, "Details parse failed", e)
            SAnime.create().apply {
                status = SAnime.COMPLETED
            }
        }
    }

    // ============================== EPISODES ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                url = response.request.url.toString()
            }
        )
    }

    // ============================== VIDEOS ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val results = mutableListOf<Video>()

        val jsRegex =
            Regex(
                """["']videoUrl["']\s*:\s*["'](https?://[^"']+)["'][^}]+["']quality["']\s*:\s*["']?(\d+)"""
            )

        document.select("script").forEach { script ->
            jsRegex.findAll(script.data()).forEach { match ->
                val url = match.groupValues[1]
                val quality = match.groupValues[2]
                results.add(
                    Video(
                        url,
                        "MP4 - ${quality}p",
                        url,
                        videoHeaders()
                    )
                )
            }
        }

        if (results.isEmpty()) {
            val hlsRegex = Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
            document.select("script").forEach { script ->
                hlsRegex.find(script.data())?.value?.let { hlsUrl ->
                    val playlistUtils = PlaylistUtils(client, headers)
                    results.addAll(
                        playlistUtils.extractFromHls(
                            hlsUrl,
                            response.request.url.toString(),
                            videoNameGen = { q -> "HLS - $q" },
                            headers = videoHeaders()
                        )
                    )
                }
            }
        }

        if (results.isEmpty()) {
            Regex("""https?://[^"' ]+\.mp4""")
                .findAll(document.html())
                .forEach { match ->
                    results.add(
                        Video(
                            match.value,
                            "MP4 - Unknown",
                            match.value,
                            videoHeaders()
                        )
                    )
                }
        }

        return results.distinctBy { it.videoUrl }
    }

    // ============================== SETTINGS ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val pref =
            preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: "best"

        return sortedWith(
            compareByDescending {
                when {
                    pref == "best" ->
                        it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                    it.quality.contains(pref) -> 1000
                    else -> 0
                }
            }
        )
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            CategoryFilter(),
            DurationFilter(),
            QualityFilter()
        )
    }

    // ============================== FILTERS ==============================

    private open class UriPartFilter(
        displayName: String,
        private val values: Array<Pair<String, String>>
    ) : AnimeFilter.Select<String>(
        displayName,
        values.map { it.first }.toTypedArray()
    ) {
        fun toUriPart(): String = values[state].second
    }

    private class CategoryFilter : UriPartFilter(
        "Category",
        arrayOf(
            "All" to "all",
            "Asian" to "asian",
            "Milf" to "milf",
            "Teen" to "teen"
        )
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            "Any" to "0",
            "10+ min" to "10",
            "20+ min" to "20"
        )
    )

    private class QualityFilter : UriPartFilter(
        "Quality",
        arrayOf(
            "Any" to "0",
            "1080p" to "1080",
            "720p" to "720"
        )
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "best"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p")
    }

    // ============================== API ==============================

    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val totalPages: Int
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("default_thumb") val thumb: ApiThumb
    ) {
        fun toSAnime(): SAnime {
            return SAnime.create().apply {
                this.url = this@ApiVideo.url
                this.title = this@ApiVideo.title
                thumbnail_url = thumb.src
                genre = keywords
                status = SAnime.COMPLETED
            }
        }
    }

    @Serializable
    private data class ApiVideoDetailResponse(
        @SerialName("url") val url: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("views") val views: Long,
        @SerialName("length_sec") val lengthSec: Int,
        @SerialName("default_thumb") val thumb: ApiThumb
    ) {
        fun toSAnime(): SAnime {
            return SAnime.create().apply {
                this.url = this@ApiVideoDetailResponse.url
                this.title = this@ApiVideoDetailResponse.title
                thumbnail_url = thumb.src
                genre = keywords
                description =
                    "Views: $views\nLength: ${lengthSec / 60} min"
                status = SAnime.COMPLETED
            }
        }
    }

    @Serializable
    private data class ApiThumb(
        @SerialName("src") val src: String
    )
}
