package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
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

    override fun popularAnimeSelector(): String =
        "div.movie-item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.title =
            element.select("h3.title").text()

        anime.setUrlWithoutDomain(
            element.selectFirst("a")!!.attr("href")
        )

        anime.thumbnail_url =
            element.selectFirst("img")!!.attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String? =
        "a.next"

    // ==============================
    // Details
    // ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title =
            document.selectFirst("h1")!!.text()

        anime.thumbnail_url =
            document.selectFirst("div.poster img")!!.attr("src")

        anime.description =
            document.selectFirst("div.description")?.text()

        return anime
    }

    // ==============================
    // Episodes
    // ==============================

    override fun episodeListSelector(): String =
        "ul.episodes li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        episode.name =
            element.selectFirst("a")!!.text()

        episode.setUrlWithoutDomain(
            element.selectFirst("a")!!.attr("href")
        )

        return episode
    }

    // ==============================
    // Videos
    // ==============================

    override fun videoListParse(document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        document.select("video source").forEach {
            val url = it.attr("src")

            videos.add(
                Video(
                    url = url,
                    quality = "Default",
                    videoUrl = url,
                ),
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
        return GET(
            "$baseUrl/en/search/$query?page=$page",
            headers,
        )
    }

    override fun searchAnimeSelector(): String =
        popularAnimeSelector()

    override fun searchAnimeFromElement(
        element: Element
    ): SAnime =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? =
        popularAnimeNextPageSelector()
}
