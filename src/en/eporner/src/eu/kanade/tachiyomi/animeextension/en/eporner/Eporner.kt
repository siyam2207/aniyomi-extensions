package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Eporner : AnimeHttpSource() {

    override val name: String = "Eporner"
    override val baseUrl: String = "https://www.eporner.com"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = network.cloudflareClient

    // --------------------------- Latest ---------------------------
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/most-recent/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }

    // --------------------------- Popular ---------------------------
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/most-viewed/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }

    // --------------------------- Search ---------------------------
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search/$query/$page/", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimeList(response)
    }

    // --------------------------- Anime Details ---------------------------
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.title").text()
            thumbnail_url = document.select("meta[property=og:image]").attr("content")
            description = document.select("div.video-description").text()
            genre = document.select("div.categories a").joinToString(", ") { it.text() }
        }
    }

    // --------------------------- Episodes ---------------------------
    override fun episodeListParse(response: Response): List<SEpisode> {
        // For this type of site, usually each video is its own "episode"
        val document = response.asJsoup()
        return listOf(
            SEpisode.create().apply {
                name = "Watch"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
            },
        )
    }

    // --------------------------- Video Links ---------------------------
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Try to extract video URLs from the page
        val scriptText = document.select("script").find {
            it.html().contains("video_url") || it.html().contains("mp4")
        }?.html() ?: ""

        // Look for mp4 URLs in the script
        val mp4Pattern = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
        val matches = mp4Pattern.findAll(scriptText)

        matches.forEach { match ->
            val url = match.value
            val quality = when {
                url.contains("1080") -> "1080p"
                url.contains("720") -> "720p"
                url.contains("480") -> "480p"
                url.contains("360") -> "360p"
                else -> "Unknown"
            }
            videos.add(Video(url, quality, url))
        }

        return videos
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    // --------------------------- Helpers ---------------------------
    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.mb h3 a").map { element ->
            SAnime.create().apply {
                title = element.text()
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.parent()?.parent()?.select("img")?.attr("src") ?: ""
            }
        }

        val hasNextPage = document.select("a.next").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // --------------------------- Required but not used ---------------------------
    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList()
    }
}
