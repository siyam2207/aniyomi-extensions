package eu.kanade.tachiyomi.animeextension.en.eporner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.animeextension.model.AnimeFilterList
import eu.kanade.tachiyomi.animeextension.model.AnimesPage
import eu.kanade.tachiyomi.animeextension.model.SAnime
import eu.kanade.tachiyomi.animeextension.model.SEpisode
import eu.kanade.tachiyomi.animeextension.model.Video
import eu.kanade.tachiyomi.animeextension.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup

class Eporner : AnimeHttpSource() {

    override val name: String = "Eporner"
    override val baseUrl: String = "https://www.eporner.com"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = network.cloudflareClient

    // --------------------------- Latest ---------------------------
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/most-recent/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimeList(response)

    // --------------------------- Popular ---------------------------
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/most-viewed/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimeList(response)

    // --------------------------- Search ---------------------------
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search/$query/$page/", headers)

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimeList(response)

    // --------------------------- Anime Details ---------------------------
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.title").text()
            thumbnail_url = document.select("meta[property=og:image]").attr("content")
            description = document.select("div.video-description").text()
            genre = document.select("div.categories a").joinToString(", ") { it.text() }
        }
    }

    // --------------------------- Episodes ---------------------------
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Watch"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
            },
        )
    }

    // --------------------------- Video Links via JSON API ---------------------------
    override fun videoListRequest(episode: SEpisode): Request {
        val videoId = episode.url.substringAfter("video-").substringBefore("/")
        val apiUrl = "$baseUrl/api/v2/video/search/?id=$videoId&per_page=1&thumbsize=big"
        return GET(apiUrl, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body?.string() ?: return emptyList()
        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val videosArray = json["videos"]?.jsonArray ?: return emptyList()
        if (videosArray.isEmpty()) return emptyList()

        val videoJson = videosArray[0].jsonObject
        val allQualities = videoJson["all_qualities"]?.jsonObject ?: return emptyList()

        val videoList = mutableListOf<Video>()
        for ((quality, urlElement) in allQualities) {
            val url = urlElement.toString().trim('"') // remove quotes
            videoList.add(Video(url, "${quality}p", url))
        }
        return videoList
    }

    // --------------------------- Helpers ---------------------------
    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.mb h3 a").map { element ->
            SAnime.create().apply {
                title = element.text()
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.parent()?.parent()?.select("img")?.attr("src") ?: ""
            }
        }

        val hasNextPage = document.select("a.next").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // --------------------------- Filters ---------------------------
    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}
