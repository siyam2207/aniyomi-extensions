package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Xmovix : ParsedAnimeHttpSource() {

    override val name = "Xmovix"

    override val baseUrl = "https://hd.xmovix.net"

    override val lang = "en"

    override val supportsLatest = true

    // ===============================
    // HEADERS
    // ===============================

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ===============================
    // POPULAR / FRONT PAGE
    // ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/en/", headers)
    }

    override fun popularAnimeSelector(): String {
        return "div.short"
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        val linkElement = element.selectFirst("a.short-poster")
        val titleElement = element.selectFirst("a.th-title")
        val imgElement = element.selectFirst("a.short-poster img")

        anime.setUrlWithoutDomain(
            linkElement?.attr("href") ?: "",
        )

        anime.title = titleElement?.text() ?: "No Title"

        anime.thumbnail_url =
            imgElement?.attr("data-src")
                ?.ifEmpty {
                    imgElement.attr("src")
                }

        return anime
    }

    override fun popularAnimeNextPageSelector(): String? {
        return null
    }

    // ===============================
    // LATEST
    // ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return popularAnimeRequest(page)
    }

    override fun latestUpdatesSelector(): String {
        return popularAnimeSelector()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun latestUpdatesNextPageSelector(): String? {
        return null
    }

    // ===============================
    // SEARCH
    // ===============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        return GET(
            "$baseUrl/en/search/$query",
            headers,
        )
    }

    override fun searchAnimeSelector(): String {
        return "div.short"
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String? {
        return null
    }

    // ===============================
    // DETAILS
    // ===============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title =
            document.selectFirst("h1")?.text() ?: ""

        anime.thumbnail_url =
            document.selectFirst("img")?.attr("src")

        anime.description =
            document.selectFirst("div.seo")?.text()

        return anime
    }

    // ===============================
    // EPISODE LIST (MOVIE = 1)
    // ===============================

    override fun episodeListSelector(): String {
        return "div.not-exist"
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        episode.name = "Movie"

        episode.setUrlWithoutDomain("")

        return episode
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episode = SEpisode.create()

        episode.name = "Movie"

        episode.setUrlWithoutDomain(
            response.request.url.toString()
                .removePrefix(baseUrl)
        )

        return listOf(episode)
    }

    // ===============================
    // VIDEO LIST (STUB)
    // ===============================

    override fun videoListSelector(): String {
        return "div.not-exist"
    }

    override fun videoFromElement(element: Element): Video {
        return Video(
            "",
            "Stub",
            "",
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        return emptyList()
    }

    override fun videoUrlParse(response: Response): String {
        return videoListParse(response)
            .firstOrNull()
            ?.videoUrl
            ?: ""
    }
}
