package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.OkHttpClient
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import eu.kanade.tachiyomi.network.GET

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = OkHttpClient()

    // -------------------
    // Popular
    // -------------------
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/videos/?o=mv&p=$page")

    override fun popularAnimeParse(response: okhttp3.Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("a.videoBox")
        val animeList = elements.map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst("a.next, a[rel=next]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("span.title")?.text() ?: "Unknown"
            thumbnail_url =
                element.selectFirst("img")?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("img")?.attr("src")
            url = element.attr("href")
        }
    }

    // -------------------
    // Latest
    // -------------------
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/videos/?o=mv&p=$page")

    override fun latestUpdatesParse(response: okhttp3.Response): AnimesPage =
        popularAnimeParse(response)

    // -------------------
    // Search
    // -------------------
    override fun searchAnimeRequest(page: Int, query: String) =
        GET("$baseUrl/search/${query.replace(" ", "+")}/?o=mv&p=$page")

    override fun searchAnimeParse(response: okhttp3.Response): AnimesPage =
        popularAnimeParse(response)

    // -------------------
    // Episodes
    // -------------------
    override fun episodeListParse(response: okhttp3.Response): List<SEpisode> {
        return listOf(SEpisode.create().apply {
            name = "Video"
            url = response.request.url.toString()
        })
    }

    // -------------------
    // Video
    // -------------------
    override fun videoListParse(response: okhttp3.Response): List<Video> {
        val document = response.asJsoup()
        val regex = Regex("""https://dash[^"]+\.mp4\.urlset/master\.m3u8[^"]*""")
        val hlsUrl = regex.find(document.html())?.value
            ?: throw Exception("HLS master playlist not found")

        return listOf(
            Video(
                url = hlsUrl,
                quality = "HLS",
                videoUrl = hlsUrl,
                headers = Headers.headersOf("Referer", baseUrl)
            )
        )
    }
}
