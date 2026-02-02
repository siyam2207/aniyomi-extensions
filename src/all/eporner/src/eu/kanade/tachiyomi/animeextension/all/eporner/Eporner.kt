package eu.kanade.tachiyomi.animeextension.all.eporner

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
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

    // ==================== Headers ====================
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json")
        .add("Origin", baseUrl)
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    // ==================== Popular & Search ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = if (query.isNotBlank()) URLEncoder.encode(query, "UTF-8") else "all"
        val url = "$apiUrl/video/search/?query=$q&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val data = json.decodeFromString(ApiSearchResponse.serializer(), body)
        val list = data.videos.map { it.toSAnime(baseUrl) }
        return AnimesPage(list, data.page < data.total_pages)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        return SAnime.create().apply {
            url = response.request.url.toString()
            val doc = response.asJsoup()

            // ALWAYS assign title - never leave lateinit var uninitialized
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown Title"

            // Safe thumbnail (nullable is OK for thumbnail_url)
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            // ALWAYS assign description
            description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() } ?: "No description available"

            // Genre can be null (lateinit var can handle null)
            val tags = doc.select("a.tag").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
            genre = if (tags.isNotEmpty()) tags.joinToString(", ") else null

            status = SAnime.COMPLETED
        }
    }

    // ==================== Episodes ====================
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Full Video"
            url = response.request.url.toString()
            episode_number = 1F
        },
    )

    // ==================== Video Extraction ====================
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val playlistUtils = PlaylistUtils(client, headers)

        document.select("script").forEach { script ->
            val js = script.html()

            // HLS streams
            Regex("""hlsUrl['"]?\s*:\s*['"]([^'"]+)['"]""").findAll(js).forEach { match ->
                val hlsUrl = match.groupValues[1]
                if (hlsUrl.isNotBlank()) {
                    videos.addAll(
                        playlistUtils.extractFromHls(
                            hlsUrl,
                            response.request.url.toString(),
                            videoNameGen = { q -> "HLS - $q" },
                        ),
                    )
                }
            }

            // MP4 sources
            Regex("""videoUrl['"]?\s*:\s*['"]([^'"]+\.mp4)['"]""").findAll(js).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    val quality = url.extractQuality()
                    videos.add(Video(url, "MP4 - $quality", url))
                }
            }

            // Alternative MP4 pattern
            Regex("""['"]?src['"]?\s*:\s*['"]([^'"]+\.mp4[^'"]*)['"]""").findAll(js).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) {
                    val quality = url.extractQuality()
                    videos.add(Video(url, "MP4 - $quality", url))
                }
            }
        }

        return videos.distinctBy { it.quality }.sortedByDescending { it.quality.extractInt() }
    }

    override fun videoUrlParse(response: Response): String = videoListParse(response).firstOrNull()?.videoUrl ?: ""

    // ==================== Helpers ====================
    private fun String.extractQuality(): String = when {
        contains("1080") -> "1080p"
        contains("720") -> "720p"
        contains("480") -> "480p"
        contains("360") -> "360p"
        contains("240") -> "240p"
        else -> "Unknown"
    }

    private fun String.extractInt(): Int = Regex("""\d+""").find(this)?.value?.toInt() ?: 0

    // ==================== Placeholders ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
    override fun getFilterList() = AnimeFilterList()

    // ==================== API Classes ====================
    @Serializable
    data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
    )

    @Serializable
    data class ApiVideo(
        @SerialName("title") val title: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("views") val views: Long,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
            // ALWAYS assign title - never leave lateinit var uninitialized
            this.title = title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            
            // Safe URL assignment
            this.url = embed.takeIf { it.isNotBlank() } ?: baseUrl

            // Safe thumbnail (nullable is OK)
            this.thumbnail_url = defaultThumb.src.takeIf { it.isNotBlank() }

            // ALWAYS assign description
            this.description = if (views > 0) "Views: $views" else "No view count available"

            // Genre can be null (lateinit var can handle null)
            this.genre = keywords.takeIf { it.isNotBlank() }

            this.status = SAnime.COMPLETED
        }
    }

    @Serializable
    data class ApiThumbnail(@SerialName("src") val src: String)
}
