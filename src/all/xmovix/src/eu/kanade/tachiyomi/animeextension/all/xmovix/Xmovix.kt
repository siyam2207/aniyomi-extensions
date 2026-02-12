package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    // ==============================
    // Popular
    // ==============================
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/en?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun popularAnimeSelector(): String = "div.movie-item"
    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.select("h3.title").text()
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }
    private fun popularAnimeNextPageSelector(): String? = "a.next"

    // ==============================
    // Latest
    // ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==============================
    // Search
    // ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/en/search/$query?page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==============================
    // Details
    // ==============================
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = document.selectFirst("div.poster img")!!.attr("src")
            description = document.selectFirst("div.description")?.text()
        }
    }

    // ==============================
    // Episodes
    // ==============================
    override fun episodeListRequest(anime: SAnime): Request =
        GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body.string())
        return document.select("ul.episodes li").map { element ->
            SEpisode.create().apply {
                name = element.selectFirst("a")!!.text()
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            }
        }
    }

    // ==============================
    // Videos
    // ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        return document.select("video source").map { element ->
            val url = element.attr("src")
            Video(url, "Default", url, headers = headers)
        }
    }

    // ✅ REQUIRED by AnimeHttpSource
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }
}
