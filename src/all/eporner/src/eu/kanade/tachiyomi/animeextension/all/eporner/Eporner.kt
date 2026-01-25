package eu.kanade.tachiyomi.animesource.all.eporner

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Eporner : ParsedHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    override fun client(): OkHttpClient = network.client

    // -------------------
    // Popular Videos
    // -------------------
    override fun popularAnimeRequest(page: Int) =
        GET(
            "$baseUrl/videos/?o=mv&p=$page",
        )

    override fun popularAnimeSelector(): String = "a.videoBox"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("span.title")?.text() ?: "Unknown"
            thumbnail_url =
                element.selectFirst("img")
                    ?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("img")?.attr("src")
            url = element.attr("href")
        }
    }

    override fun popularAnimeNextPageSelector(): String =
        "a.next, a[rel=next]"

    // -------------------
    // Latest Videos
    // -------------------
    override fun latestUpdatesRequest(page: Int) =
        GET(
            "$baseUrl/videos/?o=mv&p=$page",
        )

    override fun latestUpdatesSelector(): String = "a.videoBox"

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String =
        "a.next, a[rel=next]"

    // -------------------
    // Search
    // -------------------
    override fun searchAnimeRequest(page: Int, query: String) =
        GET(
            "$baseUrl/search/${query.replace(" ", "+")}/?o=mv&p=$page",
        )

    override fun searchAnimeSelector(): String = "a.videoBox"

    override fun searchAnimeFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String =
        "a.next, a[rel=next]"

    // -------------------
    // Episode List
    // -------------------
    override fun episodeListParse(document: Document): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                url = document.location()
            },
        )
    }

    override fun episodeListSelector(): String = ""

    override fun episodeFromElement(element: Element): SEpisode =
        SEpisode.create()

    // -------------------
    // Video Playback
    // -------------------
    override fun videoListParse(document: Document): List<Video> {
        val regex =
            Regex(
                """https://dash[^"]+\.mp4\.urlset/master\.m3u8[^"]*""",
            )

        val hlsUrl =
            regex.find(document.html())?.value
                ?: error("HLS master playlist not found")

        return listOf(
            Video(
                url = hlsUrl,
                quality = "HLS",
                videoUrl = hlsUrl,
                headers = Headers.headersOf(
                    "Referer",
                    baseUrl,
                ),
            ),
        )
    }

    override fun videoListSelector(): String = ""

    override fun videoFromElement(element: Element): Video =
        Video(
            url = "",
            quality = "",
            videoUrl = "",
            headers = Headers.headersOf(),
        )

    override fun videoUrlParse(document: Document): String = ""
}
