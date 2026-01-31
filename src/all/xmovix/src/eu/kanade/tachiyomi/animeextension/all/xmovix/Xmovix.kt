package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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
    override val lang = "all"
    override val supportsLatest = true
    override val baseUrl = "https://hd.xmovix.net"

    override val client: OkHttpClient = OkHttpClient()

    // =====================
    // Popular
    // =====================
    override fun popularAnimeRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val list = document.select("div.shortstory").map { element ->
            SAnime.create().apply {
                val a = element.selectFirst("a") ?: return@map null
                title = a.text()
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url =
                    element.selectFirst("img")?.attr("abs:src")
            }
        }.filterNotNull()

        return AnimesPage(list, false)
    }

    // =====================
    // Latest
    // =====================
    override fun latestUpdatesRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =====================
    // Search
    // =====================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request =
        GET(
            "$baseUrl/index.php?do=search&subaction=search&story=$query",
            headers,
        )

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =====================
    // Details
    // =====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description =
                document.selectFirst("div.fullstory")?.text()
            thumbnail_url =
                document.selectFirst("img")?.attr("abs:src")
            status = SAnime.UNKNOWN
        }
    }

    // =====================
    // Episodes (single dummy)
    // =====================
    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Play"
                episode_number = 1f
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
}
