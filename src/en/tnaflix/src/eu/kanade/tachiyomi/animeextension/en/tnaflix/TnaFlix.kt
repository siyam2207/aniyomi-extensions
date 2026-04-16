package eu.kanade.tachiyomi.animeextension.en.tnaflix

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
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
            "$baseUrl/featured/"
        } else {
            "$baseUrl/featured/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "div.row.video-list > div.mb-3"
    override fun popularAnimeNextPageSelector() = "li.pagination-next a.page-link"
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

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        return GET("$baseUrl/search?what=$encodedQuery&page=$page", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
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
            },
        )
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoUrl = extractVideoUrl(document)
            ?: throw Exception("No video URL found")

        val refererHeader = "Referer: $baseUrl"

        return if (videoUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(videoUrl, refererHeader, videoNameGen = { quality -> "TnaFlix - $quality" })
        } else {
            val headers = headersBuilder()
                .add("Referer", baseUrl)
                .build()
            listOf(Video(videoUrl, "TnaFlix", videoUrl, headers))
        }
    }

    private fun extractVideoUrl(document: Document): String? {
        val videoSource = document.selectFirst("video source")
        videoSource?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }

        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            val patterns = listOf(
                Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""video_url:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""source:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
                Regex(""""file":"([^"]+\.m3u8[^"]*)""""),
            )
            for (pattern in patterns) {
                val match = pattern.find(data)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

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
        val thumbLink = element.selectFirst("a.thumb")
        val url = thumbLink?.attr("href") ?: ""
        val titleElem = element.selectFirst("a.video-title")
        val title = titleElem?.text()?.trim() ?: "Unknown"
        val img = thumbLink?.selectFirst("img")
        val thumbnail = img?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }

        return SAnime.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = thumbnail
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
