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
import org.jsoup.nodes.Element

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    // ==============================
    // Popular – front page (New Porn Movies)
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

    // Selector for each movie card
    private fun popularAnimeSelector(): String = "div.short"

    // Extract SAnime from one movie card
    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        // Title is inside a.th-title
        title = element.selectFirst("a.th-title")?.text() ?: ""

        // Relative URL from the poster link
        val posterLink = element.selectFirst("a.short-poster")
        val fullUrl = posterLink?.attr("href") ?: ""
        setUrlWithoutDomain(fullUrl.substringAfter(baseUrl))

        // Thumbnail: try src, fallback to data-src
        val img = posterLink?.selectFirst("img")
        thumbnail_url = when {
            img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
            else -> img?.attr("data-src")
        }
    }

    // Next page link – adjust if the site uses different pagination
    private fun popularAnimeNextPageSelector(): String? = "a.next"

    // ==============================
    // Latest (reuse popular logic)
    // ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==============================
    // Search – /en/search/QUERY?page=PAGE
    // ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/en/search/$query?page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ==============================
    // Anime Details – page of one movie
    // ==============================
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
            description = document.selectFirst("div.description")?.text()
        }
    }

    // ==============================
    // Episodes – single episode (the movie itself)
    // ==============================
    override fun episodeListRequest(anime: SAnime): Request =
        GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        // Xmovix presents each movie as a single episode
        val episode = SEpisode.create().apply {
            name = "Movie"
            episode_number = 1f
            url = response.request.url.toString()
        }
        return listOf(episode)
    }

    // ==============================
    // Videos – extract direct mp4 from <video> tag
    // ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        return document.select("video source").map { element ->
            val videoUrl = element.attr("src")
            Video(videoUrl, "Default", videoUrl, headers = headers)
        }
    }

    // Required by AnimeHttpSource
    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""
}
