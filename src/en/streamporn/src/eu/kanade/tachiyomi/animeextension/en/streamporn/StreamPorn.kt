package eu.kanade.tachiyomi.animeextension.en.streamporn

import android.util.Log
import eu.kanade.tachiyomi.animeextension.en.streamporn.getFilterList
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
        return parseMovies(document)
    }

    // ========== LATEST UPDATES ==========
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/movies/page/$page/", headers)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ========== SEARCH & SECTION BROWSING ==========
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val section = filters.find { it is SectionFilter } as? SectionFilter
        val path = section?.getPath() ?: ""

        // 🔥 STUDIOS BROWSING
        if (path == "studios") {
            val url = if (page > 1) {
                "$baseUrl/studios/page/$page/"
            } else {
                "$baseUrl/studios/"
            }
            return GET(url, headers)
        }

        // 🔎 NORMAL SEARCH (with query)
        if (query.isNotBlank()) {
            return GET("$baseUrl/?s=$query&page=$page", headers)
        }

        // REGULAR SECTION (Movies, Most Viewed, Most Rating)
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

    // ========== PARSERS ==========
    private fun parseMovies(document: Document): AnimesPage {
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

    private fun parseStudios(document: Document): AnimesPage {
        // Each studio is an <a> inside a <span class="item">
        val studios = document.select("span.item a[href]").mapNotNull { element ->
            val title = element.attr("title").ifBlank { element.text() }
            val url = element.attr("href")
            if (title.isBlank() || url.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(url)
                // Some studios have a favicon; use it as thumbnail if available
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        val hasNextPage = document.select("a.next").isNotEmpty()
        return AnimesPage(studios, hasNextPage)
    }

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
        val document = response.asJsoup()
        val title = document.selectFirst("h3[itemprop=name]")?.text() ?: "Movie"
        return listOf(
            SEpisode.create().apply {
                name = title
                episode_number = 1f
                setUrlWithoutDomain(document.location())
            },
        )
    }

    // ========== VIDEO LIST ==========
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverLinks = document.select("div#pettabs div.Rtable1-cell a")
        if (serverLinks.isEmpty()) {
            Log.w("StreamPorn", "No server links found on page")
        }

        val videos = mutableListOf<Video>()

        serverLinks.forEach { link ->
            val serverUrl = link.attr("abs:href")
            val serverName = link.text().ifEmpty { "Server" }
            val host = serverUrl.substringAfter("://").substringBefore("/")
            Log.d("StreamPorn", "Processing server: $serverName ($host) at $serverUrl")

            val extractorVideos = try {
                when {
                    "dood" in host || "doply" in host -> {
                        Log.d("StreamPorn", "Using DoodExtractor for $serverName")
                        doodExtractor.videosFromUrl(serverUrl)
                    }
                    "lulu" in host -> {
                        Log.d("StreamPorn", "Using LuluExtractor for $serverName")
                        luluExtractor.videosFromUrl(serverUrl, serverName)
                    }
                    "mixdrop" in host -> {
                        Log.d("StreamPorn", "Using MixDropExtractor for $serverName")
                        mixDropExtractor.videosFromUrl(serverUrl, serverName)
                    }
                    "streamtape" in host -> {
                        Log.d("StreamPorn", "Using StreamTapeExtractor for $serverName")
                        streamTapeExtractor.videosFromUrl(serverUrl, serverName)
                    }
                    "voe.sx" in host -> {
                        Log.d("StreamPorn", "Using VoeExtractor for $serverName")
                        voeExtractor.videosFromUrl(serverUrl, serverName)
                    }
                    else -> {
                        Log.d("StreamPorn", "No dedicated extractor, falling back to generic")
                        runCatching {
                            val embedDoc = client.newCall(GET(serverUrl)).execute().asJsoup()
                            extractGeneric(embedDoc, serverUrl, headers)
                        }.getOrDefault(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamPorn", "Exception while extracting from $serverName", e)
                emptyList()
            }

            if (extractorVideos.isEmpty()) {
                Log.w("StreamPorn", "No videos extracted from $serverName")
            } else {
                Log.i("StreamPorn", "Extracted ${extractorVideos.size} videos from $serverName")
            }
            videos.addAll(extractorVideos)
        }

        if (videos.isEmpty()) {
            Log.e("StreamPorn", "No videos found at all")
        }
        return videos
    }

    // Generic extractor for unknown embed pages
    private fun extractGeneric(document: Document, url: String, headers: Headers): List<Video> {
        val videoTag = document.selectFirst("video source") ?: document.selectFirst("video")
        if (videoTag != null) {
            val videoUrl = videoTag.attr("src")
            Log.d("StreamPorn", "Generic: found direct video tag: $videoUrl")
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
            if (videos.isNotEmpty()) {
                Log.d("StreamPorn", "Generic: extracted ${videos.size} videos from script")
                return videos
            }
        }

        Log.d("StreamPorn", "Generic: no video found")
        return emptyList()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)

    // 🔥 Fixed: explicitly specify the return type to avoid recursion
    override fun getFilterList(): AnimeFilterList = getFilterList()
}
