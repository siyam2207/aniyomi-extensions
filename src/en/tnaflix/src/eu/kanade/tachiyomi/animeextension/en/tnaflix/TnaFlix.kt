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
            "$baseUrl/new/"
        } else {
            "$baseUrl/new/page/$page/"
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
        var title = document.selectFirst("h1.video-title")?.text()
            ?: document.selectFirst("title")?.text() ?: "Unknown"
        title = cleanTitle(title)

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
        val videos = mutableListOf<Video>()

        val videoSources = document.select("video source")
        if (videoSources.isNotEmpty()) {
            for (source in videoSources) {
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    var quality = source.attr("size").takeIf { it.isNotBlank() } ?: ""
                    if (quality.isBlank()) {
                        quality = extractQualityFromUrl(videoUrl)
                    }
                    val label = if (quality == "2160") "2160p (4K)" else "${quality}p"
                    val headers = headersBuilder()
                        .add("Referer", baseUrl)
                        .build()
                    videos.add(Video(videoUrl, label, videoUrl, headers))
                }
            }
            return videos.sortedWith(videoQualityComparator())
        }

        val hlsUrl = extractHlsUrl(document)
        if (hlsUrl != null) {
            val refererHeader = "Referer: $baseUrl"
            return playlistUtils.extractFromHls(hlsUrl, refererHeader, videoNameGen = { quality -> "TnaFlix - $quality" })
        }

        throw Exception("No video sources found")
    }

    private fun extractQualityFromUrl(url: String): String {
        val patterns = listOf(
            Regex("-(\\d{3,4})p-"),
            Regex("-(\\d{3,4})p\\?"),
            Regex("-(4k)-", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val value = match.groupValues[1].lowercase()
                return if (value == "4k") "2160" else value
            }
        }
        return "Unknown"
    }

    private fun extractNumericQuality(qualityLabel: String): Int {
        return when {
            qualityLabel.contains("2160p") || qualityLabel.contains("4K") -> 2160
            qualityLabel.contains("1080p") -> 1080
            qualityLabel.contains("720p") -> 720
            qualityLabel.contains("480p") -> 480
            qualityLabel.contains("360p") -> 360
            qualityLabel.contains("240p") -> 240
            qualityLabel.contains("144p") -> 144
            else -> qualityLabel.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun videoQualityComparator(): Comparator<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)?.toIntOrNull() ?: 1080
        return compareByDescending<Video> { video ->
            // First, prioritize the preferred quality exactly
            if (extractNumericQuality(video.quality) == preferredQuality) 1 else 0
        }.thenByDescending {
            extractNumericQuality(it.quality)
        }
    }

    private fun extractHlsUrl(document: Document): String? {
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
        return null
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("\\s*[-|]\\s*TnaFlix\\.?com\\s*$"), "")
            .replace(Regex("\\s*[-|]\\s*TnaFlix\\s*$"), "")
            .replace(Regex("\\s*\\|\\s*TnaFlix.*$"), "")
            .trim()
    }

    private fun animeFromElement(element: Element): SAnime {
        val thumbLink = element.selectFirst("a:has(img)")
        val url = thumbLink?.attr("href") ?: ""

        val titleElem = element.selectFirst("a.video-title")
        var title = titleElem?.text()?.trim() ?: "Unknown"
        title = cleanTitle(title)

        val img = thumbLink?.selectFirst("img")
        var thumbnail: String? = null
        if (img != null) {
            var src = img.attr("data-src")
            if (src.isNullOrBlank()) {
                src = img.attr("src")
            }
            if (src.isNotBlank() && !src.contains("placeholder")) {
                thumbnail = if (src.startsWith("http")) src else "$baseUrl$src"
            }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    override fun List<Video>.sort(): List<Video> {
        // This method is called by the app to sort videos; use our comparator
        return sortedWith(videoQualityComparator())
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
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p (4K)", "1080p", "720p", "480p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = arrayOf("2160", "1080", "720", "480", "360", "240", "144")
    }
}
