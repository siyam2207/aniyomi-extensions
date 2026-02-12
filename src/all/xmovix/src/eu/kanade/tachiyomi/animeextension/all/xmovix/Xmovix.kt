package eu.kanade.tachiyomi.animeextension.all.xmovix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
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

    private val tag = "Xmovix"

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
        // ✅ store relative path – setUrlWithoutDomain removes the domain
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
        // ✅ must be absolute – prepend baseUrl
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
        // ✅ same as above – absolute URL
        GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(SEpisode.create().apply {
            name = "Movie"
            episode_number = 1f
            // ✅ store absolute URL for later video extraction
            url = response.request.url.toString()
        })
    }

    // ============================== Videos ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        // ✅ episode.url is absolute (from episodeListParse)
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val document = Jsoup.parse(html)
        val videos = mutableListOf<Video>()

        document.select("div.fplay.tabs-b.video-box").forEach { container ->
            val containerDiv = container.selectFirst("div[id]") ?: return@forEach
            val containerId = containerDiv.id()

            val script = document.select("script")
                .firstOrNull { it.data().contains("'click', '#$containerId'") }
                ?.data()
                ?: return@forEach

            val iframeUrl = Regex("""s2\.src\s*=\s*["']([^"']+)["']""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?: return@forEach

            val resolved = resolveEmbed(iframeUrl, response.request.url.toString())
            videos.addAll(resolved)
        }

        // fallback – direct <video> (rare)
        if (videos.isEmpty()) {
            document.select("video source").forEach { source ->
                val url = source.attr("src")
                videos.add(Video(url, "Default", url, headers))
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun resolveEmbed(embedUrl: String, referer: String): List<Video> {
        return when {
            "myvidplay.com" in embedUrl || "streamwish" in embedUrl -> {
                try {
                    StreamWishExtractor(client, headers).videosFromUrl(embedUrl, referer)
                } catch (e: Exception) {
                    Log.e(tag, "StreamWish extractor failed", e)
                    emptyList()
                }
            }
            "filmcdn.top" in embedUrl || "filmcdm.top" in embedUrl -> {
                extractFilmCdn(embedUrl, referer)
            }
            else -> {
                try {
                    UniversalExtractor(client, headers).extractFromUrl(embedUrl, referer)
                } catch (e: Exception) {
                    Log.e(tag, "Universal extractor failed", e)
                    emptyList()
                }
            }
        }
    }

    private fun extractFilmCdn(embedUrl: String, referer: String): List<Video> {
        val headers = headersBuilder()
            .add("Referer", referer)
            .build()

        return try {
            val doc = client.newCall(GET(embedUrl, headers)).execute().use { response ->
                Jsoup.parse(response.body.string())
            }

            // direct <video> source
            doc.select("video source").map { element ->
                val url = element.attr("src")
                Video(url, "Default", url, headers)
            }.ifEmpty {
                // og:video meta
                val og = doc.selectFirst("meta[property='og:video']")?.attr("content")
                if (!og.isNullOrBlank()) {
                    listOf(Video(og, "Default", og, headers))
                } else {
                    // JavaScript variable
                    val js = doc.select("script").joinToString("\n") { it.data() }
                    Regex("""(?:file|src):\s*["']([^"']+)["']""").find(js)?.groupValues?.get(1)?.let { url ->
                        listOf(Video(url, "Default", url, headers))
                    } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "FilmCdn extractor error", e)
            emptyList()
        }
    }

    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""
}
