package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.Exception

class Eporner : AnimeHttpSource() {

    override val name: String = "Eporner"
    override val baseUrl: String = "https://www.eporner.com"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = network.cloudflareClient

    // --------------------------- Popular ---------------------------

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/popular/?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("div.video-block").map { element ->
            SAnime.create().apply {
                title = element.select("a.title").text()
                setUrlWithoutDomain(element.select("a.title").attr("href"))
                thumbnail_url = element.select("img").attr("src")
            }
        }
    }

    // --------------------------- Episodes ---------------------------

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("ul.video-list li").map { element ->
            SEpisode.create().apply {
                name = element.select("a.title").text()
                setUrlWithoutDomain(element.select("a").attr("href"))
            }
        }
    }

    // --------------------------- Video Links ---------------------------

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        val videos = mutableListOf<Video>()
        document.select("source[type=video/mp4]").forEach { element ->
            val url = element.attr("src")
            val quality = element.attr("res") ?: "HD"
            videos.add(Video(url, quality, url))
        }
        return videos
    }

    // --------------------------- Helpers ---------------------------

    private fun GET(url: String, headers: Map<String, String> = emptyMap()): Request {
        return Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search/?q=$query&page=$page"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): List<SAnime> {
        val document = Jsoup.parse(response.body!!.string())
        return document.select("div.video-block").map { element ->
            SAnime.create().apply {
                title = element.select("a.title").text()
                setUrlWithoutDomain(element.select("a").attr("href"))
                thumbnail_url = element.select("img").attr("src")
            }
        }
    }
}
