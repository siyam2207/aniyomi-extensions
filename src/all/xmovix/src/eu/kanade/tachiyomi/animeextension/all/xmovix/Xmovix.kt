package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val lang = "all"
    override val supportsLatest = true

    // IMPORTANT: must include /en
    override val baseUrl = "https://hd.xmovix.net/en"

    override val client = OkHttpClient()

    override val headers: Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0")
        .add("Referer", "https://hd.xmovix.net/")
        .build()

    // =====================
    // Popular
    // =====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document.select("div.shortstory").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null

            SAnime.create().apply {
                title = a.text().trim()
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =====================
    // Latest
    // =====================
    override fun latestUpdatesRequest(page: Int): Request =
        popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =====================
    // Search
    // =====================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url =
            "$baseUrl/index.php?do=search&subaction=search&story=$query"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =====================
    // Details
    // =====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: ""
            description =
                document.selectFirst("div.fullstory")?.text()?.trim()
            thumbnail_url =
                document.selectFirst("div.fullstory img")?.attr("abs:src")
            status = SAnime.UNKNOWN
        }
    }

    // =====================
    // Episodes (single dummy)
    // =====================
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
