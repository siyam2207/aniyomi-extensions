package eu.kanade.tachiyomi.animeextension.en.tnaflix

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TnaFlix : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "TnaFlix"
    override val baseUrl = "https://www.tnaflix.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/most-popular/"
        } else {
            "$baseUrl/most-popular/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div.videoWrapper"
    override fun popularAnimeNextPageSelector(): String = "a.pagNext"
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/newest/"
        } else {
            "$baseUrl/newest/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        return GET("$baseUrl/search?what=$encodedQuery&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val title = document.selectFirst("h1.video-title")?.text()
            ?: document.selectFirst("title")?.text()?.substringBefore(" | TnaFlix") ?: "Unknown"
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.description p")?.text()
        val genres = document.select("a[href*=category]").joinToString { it.text() }

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = thumbnail
            this.description = description
            genre = genres
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                url = anime.url
            }
        )
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoUrl = extractVideoUrl(document)
            ?: throw Exception("No video URL found")

        val headers = headersBuilder()
            .add("Referer", baseUrl)
            .build()

        return if (videoUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoUrl, headers, videoNameGen = { quality -> "TnaFlix - $quality" })
        } else {
            listOf(Video(videoUrl, "TnaFlix", videoUrl, headers))
        }
    }

    private fun extractVideoUrl(document: Document): String? {
        // Try to find video source in video tag
        val videoSource = document.selectFirst("video source")
        videoSource?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }

        // Try to find in script containing "file" or "video_url"
        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            val patterns = listOf(
                Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""video_url:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""source:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex(""""file":"([^"]+\.m3u8[^"]*)"""")
            )
            for (pattern in patterns) {
                val match = pattern.find(data)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

        // Try to find iframe src (might contain the actual player)
        val iframe = document.selectFirst("iframe[src*=player]")
        iframe?.attr("abs:src")?.takeIf { it.isNotBlank() }?.let {
            return extractVideoUrlFromIframe(it)
        }

        return null
    }

    private fun extractVideoUrlFromIframe(iframeUrl: String): String? {
        return runCatching {
            val iframeDoc = client.newCall(GET(iframeUrl, headers)).execute().asJsoup()
            extractVideoUrl(iframeDoc)
        }.getOrNull()
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun animeFromElement(element: Element): SAnime {
        val a = element.selectFirst("a")
        val img = element.selectFirst("img")
        return SAnime.create().apply {
            setUrlWithoutDomain(a?.attr("href") ?: "")
            title = img?.attr("alt") ?: a?.attr("title") ?: "Unknown"
            thumbnail_url = img?.attr("src") ?: img?.attr("data-src")
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
    }
}
