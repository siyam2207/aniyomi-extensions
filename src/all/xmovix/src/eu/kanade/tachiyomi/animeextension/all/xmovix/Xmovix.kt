package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class Xmovix : ParsedAnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    // ==============================
    // Popular
    // ==============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/en?page=$page", headers)

    override fun popularAnimeSelector() = "div.movie-item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("h3.title").text()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.thumbnail_url =
            element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = "a.next"

    // ==============================
    // Details
    // ==============================

    override fun animeDetailsParse(document: org.jsoup.nodes.Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.select("h1").text()
        anime.thumbnail_url =
            document.select("div.poster img").attr("src")

        anime.description =
            document.select("div.description").text()

        return anime
    }

    // ==============================
    // Episodes
    // ==============================

    override fun episodeListSelector() = "ul.episodes li"

    override fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()

        ep.name = element.select("a").text()
        ep.setUrlWithoutDomain(
            element.select("a").attr("href")
        )

        return ep
    }

    // ==============================
    // Video links
    // ==============================

    override fun videoListParse(
        document: org.jsoup.nodes.Document
    ): List<Video> {

        val videos = mutableListOf<Video>()

        document.select("video source").forEach {
            val url = it.attr("src")
            videos.add(
                Video(url, "Default", url)
            )
        }

        return videos
    }

    // ==============================
    // Search
    // ==============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Request {
        return GET("$baseUrl/en/search/$query?page=$page", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) =
        popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() =
        popularAnimeNextPageSelector()
}
