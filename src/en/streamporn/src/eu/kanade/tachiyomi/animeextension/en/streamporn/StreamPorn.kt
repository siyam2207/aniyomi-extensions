package eu.kanade.tachiyomi.animeextension.en.streamporn

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class StreamPorn : AnimeHttpSource() {

    override val name = "StreamPorn"
    override val baseUrl = "https://streamporn.nl"
    override val lang = "en"
    override val supportsLatest = true

    // ================= POPULAR =================
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseMovies(response.asJsoup())
    }

    // ================= LATEST =================
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/movies/page/$page/", headers)

    override fun latestUpdatesParse(response: Response) =
        popularAnimeParse(response)

    // ================= SEARCH & SECTION =================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Request {

        val section = filters.find { it is SectionFilter } as? SectionFilter
        val path = section?.getPath() ?: ""

        // 🔥 Studios browse
        if (path == "studios") {
            val url = if (page > 1) {
                "$baseUrl/studios/page/$page/"
            } else {
                "$baseUrl/studios/"
            }
            return GET(url, headers)
        }

        // 🔎 Normal search
        if (query.isNotBlank()) {
            return GET("$baseUrl/?s=$query&page=$page", headers)
        }

        val url = when {
            path.isBlank() -> "$baseUrl/page/$page/"
            else -> "$baseUrl/$path/page/$page/"
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        return if (url.contains("/studios")) {
            parseStudios(document)
        } else {
            parseMovies(document)
        }
    }

    // ================= PARSERS =================
    private fun parseMovies(document: Document): AnimesPage {
        val animes = document.select("div.ml-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(a.attr("href"))
                title = element.selectFirst("span.mli-info h2")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }

        val hasNextPage =
            document.select("a.next, a:contains(Next)").isNotEmpty()

        return AnimesPage(animes, hasNextPage)
    }

    private fun parseStudios(document: Document): AnimesPage {
        val studios = document.select("div.item a").mapNotNull { element ->

            val title = element.attr("title").ifBlank { element.text() }
            val url = element.attr("href")
            val thumb = element.selectFirst("img")?.attr("src")

            if (title.isBlank() || url.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(url)
                thumbnail_url = thumb
            }
        }

        val hasNextPage = document.select("a.next").isNotEmpty()
        return AnimesPage(studios, hasNextPage)
    }

    // ================= DETAILS =================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h3[itemprop=name]")?.text() ?: ""
            description =
                document.select("div.mvic-info p").joinToString("\n") { it.text() }
            thumbnail_url =
                document.selectFirst("div.thumb.mvic-thumb img")?.attr("src")
        }
    }

    // ================= EPISODES =================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val title =
            document.selectFirst("h3[itemprop=name]")?.text() ?: "Movie"

        return listOf(
            SEpisode.create().apply {
                name = title
                episode_number = 1f
                setUrlWithoutDomain(document.location())
            }
        )
    }

    // ================= VIDEOS =================
    override fun videoListParse(response: Response): List<Video> {
        Log.d("StreamPorn", "Video extraction not included in this snippet")
        return emptyList()
    }
}
