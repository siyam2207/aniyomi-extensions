package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Eporner : AnimeHttpSource() {

    override val name: String = "Eporner"
    override val baseUrl: String = "https://www.eporner.com"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = network.cloudflareClient

    private val jsonParser = Json { ignoreUnknownKeys = true }

    // --------------------------- Latest ---------------------------
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/v1/videos/recent?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseJsonAnimeList(response)
    }

    // --------------------------- Popular ---------------------------
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/api/v1/videos/popular?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseJsonAnimeList(response)
    }

    // --------------------------- Search ---------------------------
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/api/v1/videos/search?query=$query&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseJsonAnimeList(response)
    }

    // --------------------------- Anime Details ---------------------------
    override fun animeDetailsParse(response: Response): SAnime {
        val json = jsonParser.parseToJsonElement(response.body!!.string()).jsonObject
        val video = json["data"]!!.jsonArray.first().jsonObject

        return SAnime.create().apply {
            title = video["title"]!!.jsonObject["text"]?.toString()?.replace("\"", "") ?: "Unknown"
            thumbnail_url = video["thumbnail_url"]?.toString()?.replace("\"", "") ?: ""
            description = video["description"]?.toString()?.replace("\"", "") ?: ""
            genre = video["categories"]?.jsonArray?.joinToString(", ") { it.jsonObject["name"]!!.toString().replace("\"", "") } ?: ""
        }
    }

    // --------------------------- Episodes ---------------------------
    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = jsonParser.parseToJsonElement(response.body!!.string()).jsonObject
        val videoId = json["data"]!!.jsonArray.first().jsonObject["id"]!!.toString().replace("\"", "")

        return listOf(
            SEpisode.create().apply {
                name = "Watch"
                episode_number = 1F
                setUrlWithoutDomain("/videos/$videoId")
            }
        )
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    // --------------------------- Video Links ---------------------------
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Extract mp4 links from <video> tags or scripts
        document.select("video source").forEach { element ->
            val url = element.attr("src")
            val quality = when {
                url.contains("1080") -> "1080p"
                url.contains("720") -> "720p"
                url.contains("480") -> "480p"
                url.contains("360") -> "360p"
                else -> "Unknown"
            }
            videos.add(Video(url, quality, url))
        }

        return videos
    }

    // --------------------------- Helpers ---------------------------
    private fun parseJsonAnimeList(response: Response): AnimesPage {
        val json = jsonParser.parseToJsonElement(response.body!!.string()).jsonObject
        val dataArray = json["data"]!!.jsonArray

        val animeList = dataArray.map { item ->
            val obj = item.jsonObject
            SAnime.create().apply {
                title = obj["title"]!!.toString().replace("\"", "")
                setUrlWithoutDomain("/videos/${obj["id"]!!.toString().replace("\"", "")}")
                thumbnail_url = obj["thumbnail_url"]?.toString()?.replace("\"", "") ?: ""
            }
        }

        val hasNextPage = json["meta"]?.jsonObject?.get("next_page") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // --------------------------- Required but not used ---------------------------
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
