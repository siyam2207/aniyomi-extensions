package eu.kanade.tachiyomi.animeextension.en.streamporn

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class StreamPorn : AnimeHttpSource() {

    override val name = "StreamPorn"
    override val baseUrl = "https://streamporn.nl"
    override val lang = "en"
    override val supportsLatest = true

    // Video extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    // ========== POPULAR (Homepage) ==========
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.ml-item").mapNotNull { element ->
            SAnime.create().apply {
                val a = element.selectFirst("a") ?: return@mapNotNull null
                setUrlWithoutDomain(a.attr("href"))
                title = element.selectFirst("span.mli-info h2")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        val hasNextPage = document.select("a.next, a:contains(Next)").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ========== LATEST UPDATES ==========
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/movies/page/$page/", headers)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ========== SEARCH ==========
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/?s=$query&page=$page", headers)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ========== ANIME DETAILS ==========
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h3[itemprop=name]")?.text() ?: ""
            description = document.selectFirst("div[itemprop=description].desc")?.text()
                ?: document.select("div.mvic-info p").joinToString("\n") { it.text() }
            thumbnail_url = document.selectFirst("div.thumb.mvic-thumb img")?.attr("src")
        }
    }

    // ========== EPISODE LIST ==========
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Return a single episode representing the movie
        val document = response.asJsoup()
        val title = document.selectFirst("h3[itemprop=name]")?.text() ?: "Movie"
        return listOf(
            SEpisode.create().apply {
                name = title
                episode_number = 1f
                // Store the anime page URL as the episode URL so videoListParse can re-fetch it
                setUrlWithoutDomain(document.location())
            }
        )
    }

    // ========== VIDEO LIST ==========
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverLinks = document.select("div#pettabs div.Rtable1-cell a")
        val videos = mutableListOf<Video>()

        serverLinks.forEach { link ->
            val serverUrl = link.attr("abs:href")
            val serverName = link.text().ifEmpty { "Server" }
            val host = serverUrl.substringAfter("://").substringBefore("/")

            val extractorVideos = when {
                "dood" in host || "doply" in host -> doodExtractor.videosFromUrl(serverUrl)
                "lulu" in host -> luluExtractor.videosFromUrl(serverUrl, serverName)
                "mixdrop" in host -> mixDropExtractor.videosFromUrl(serverUrl, serverName)
                "streamtape" in host -> streamTapeExtractor.videosFromUrl(serverUrl, serverName)
                "voe.sx" in host -> voeExtractor.videosFromUrl(serverUrl, serverName)
                else -> {
                    // Fallback: fetch the embed page and try generic extraction
                    runCatching {
                        val embedDoc = client.newCall(GET(serverUrl)).execute().asJsoup()
                        extractGeneric(embedDoc, serverUrl, headers)
                    }.getOrDefault(emptyList())
                }
            }
            videos.addAll(extractorVideos)
        }

        return videos
    }

    // Generic extractor for unknown embed pages
    private fun extractGeneric(document: Document, url: String, headers: Headers): List<Video> {
        val videoTag = document.selectFirst("video source") ?: document.selectFirst("video")
        if (videoTag != null) {
            val videoUrl = videoTag.attr("src")
            return listOf(Video(videoUrl, "Direct", videoUrl, headers = headers))
        }

        val script = document.select("script:containsData(sources)").firstOrNull()?.data()
        if (script != null) {
            val regex = Regex("""file:\s*['"]([^'"]+)['"]""")
            val matches = regex.findAll(script)
            val videos = mutableListOf<Video>()
            matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                videos.add(Video(videoUrl, "Source", videoUrl, headers = headers))
            }
            if (videos.isNotEmpty()) return videos
        }

        return emptyList()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)
}
