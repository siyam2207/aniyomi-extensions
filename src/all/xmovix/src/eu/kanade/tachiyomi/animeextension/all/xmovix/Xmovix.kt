package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val lang = "en"
    override val supportsLatest = true

    // IMPORTANT: real browsing path
    override val baseUrl = "https://hd.xmovix.net"

    override val client: OkHttpClient = OkHttpClient()

    // =======================
    // Popular
    // =======================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseListing(response)
    }

    // =======================
    // Latest
    // =======================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseListing(response)
    }

    // =======================
    // Search
    // =======================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList,
    ): Request {
        val url =
            "https://hd.xmovix.net/index.php?do=search&subaction=search&story=$query"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseListing(response)
    }

    // =======================
    // Shared listing parser
    // =======================
    private fun parseListing(response: Response): AnimesPage {
        val document = response.asJsoup()

        val items = document.select("div#dle-content > div")

        val animeList = items.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val img = element.selectFirst("img")

            SAnime.create().apply {
                title = link.attr("title").ifBlank { link.text() }
                setUrlWithoutDomain(link.attr("href"))

                thumbnail_url =
                    img?.attr("data-src")
                        ?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")
            }
        }

        return AnimesPage(animeList, false)
    }

    // =======================
    // Details
    // =======================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("div.fullstory")?.text()
            thumbnail_url =
                document.selectFirst("div.poster img")?.attr("src")
            status = SAnime.UNKNOWN
        }
    }

    // =======================
    // Episodes (single placeholder)
    // =======================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Play"
                episode_number = 1f
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }
}
