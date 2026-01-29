package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

class Eporner : ParsedAnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    /* ===================== POPULAR ===================== */

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/hd-porn/$page/", headers)
    }

    override fun popularAnimeSelector(): String = "div.mb"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anchor = element.selectFirst("a")!!

        return SAnime.create().apply {
            title = anchor.select("span.h5").text()
            url = anchor.attr("href")
            thumbnail_url = anchor.select("img").attr("data-src")
                .ifBlank { anchor.select("img").attr("src") }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "a.next"

    /* ===================== LATEST ===================== */

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/new-porn/$page/", headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() =
        popularAnimeNextPageSelector()

    /* ===================== SEARCH ===================== */

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Request {
        return GET("$baseUrl/search/$query/$page/", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) =
        popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() =
        popularAnimeNextPageSelector()

    /* ===================== DETAILS ===================== */

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("meta[name=description]")
                ?.attr("content")
            thumbnail_url = document.selectFirst("video[poster]")
                ?.attr("poster")
        }
    }

    /* ===================== EPISODES ===================== */

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1f
            url = response.request.url.toString()
        }
        return listOf(episode)
    }

    /* ===================== VIDEOS ===================== */

    override fun videoListParse(response: Response): List<Video> {
        val videoId = extractVideoId(response.request.url.toString())
        val apiUrl =
            "$baseUrl/api/v2/video/search/?id=$videoId&per_page=1"

        val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
        val body = apiResponse.body?.string() ?: return emptyList()

        val videoList = mutableListOf<Video>()

        val videosObj = JSONObject(body)
            .getJSONArray("videos")
            .getJSONObject(0)
            .getJSONObject("videos")

        videosObj.keys().forEach { quality ->
            val videoUrl = videosObj.getString(quality)
            videoList.add(
                Video(
                    videoUrl,
                    "Eporner $quality",
                    videoUrl
                )
            )
        }

        return videoList
    }

    /* ===================== HELPERS ===================== */

    private fun extractVideoId(url: String): String {
        return url.substringAfter("video-")
            .substringBefore("/")
    }
}
