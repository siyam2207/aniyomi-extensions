package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/en?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animes = document.select("div.short").map { element ->
            popularAnimeFromElement(element)
        }
        val hasNext = document.selectFirst("a.next") != null
        return AnimesPage(animes, hasNext)
    }

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst("a.th-title")?.text() ?: ""
        val posterLink = element.selectFirst("a.short-poster") ?: return@apply
        setUrlWithoutDomain(posterLink.attr("href"))
        val img = posterLink.selectFirst("img")
        thumbnail_url = when {
            img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
            else -> img?.attr("data-src")
        }
    }

    // ============================== Latest ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/en/search/$query?page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
            description = document.selectFirst("div.description")?.text()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                url = response.request.url.toString()
            },
        )
    }

    // ============================== Videos ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        // 🚧 VIDEO EXTRACTION TEMPORARILY DISABLED
        // To enable video extraction later:
        // 1. Parse the iframe URLs from the <script> tags.
        // 2. Write a custom extractor for filmcdn.top / filmcdm.top.
        // 3. Remove this comment and implement the extraction.
        return emptyList()
    }

    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""
}
