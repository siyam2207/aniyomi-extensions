package eu.kanade.tachiyomi.animeextension.en.noodlemagazine

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.regex.Pattern

class NoodleMagazine : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "NoodleMagazine"
    override val baseUrl = "https://noodlemagazine.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var currentPage = 0

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        currentPage = page
        val url = "$baseUrl/new-video?p=${page - 1}"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div.item"
    override fun popularAnimeNextPageSelector(): String = "" // Handled by nextPageUrl
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        currentPage = page
        val url = "$baseUrl/search?story=$query&p=${page - 1}"
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val genres = ""

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
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        val scriptData = document.select("script")
            .map { it.data() }
            .find { it.contains("window.playlist") }
        if (scriptData != null) {
            val playlistJson = extractPlaylistJson(scriptData)
            val playlist = Json.decodeFromString<Playlist>(playlistJson)
            playlist.sources.forEach { source ->
                val videoUrl = source.file
                val quality = source.label ?: extractQualityFromUrl(videoUrl)
                val headers = headersBuilder()
                    .add("Referer", baseUrl)
                    .build()
                videos.add(Video(videoUrl, quality, videoUrl, headers))
            }
            return videos.sortedWith(videoQualityComparator())
        }

        val videoSources = document.select("video source")
        if (videoSources.isNotEmpty()) {
            for (source in videoSources) {
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    val quality = source.attr("size").takeIf { it.isNotBlank() } ?: "Unknown"
                    val label = "${quality}p"
                    val headers = headersBuilder()
                        .add("Referer", baseUrl)
                        .build()
                    videos.add(Video(videoUrl, label, videoUrl, headers))
                }
            }
            return videos.sortedByDescending { extractNumericQuality(it.quality) }
        }

        throw Exception("No video sources found")
    }

    private fun extractPlaylistJson(scriptData: String): String {
        val pattern = Pattern.compile("window\\.playlist\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)
        val matcher = pattern.matcher(scriptData)
        if (matcher.find()) {
            return matcher.group(1)
        }
        throw Exception("Could not extract playlist JSON")
    }

    private fun extractQualityFromUrl(url: String): String {
        val pattern = Pattern.compile("-(\\d+)p\\.")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) "${matcher.group(1)}p" else "Unknown"
    }

    @Serializable
    data class Playlist(
        val sources: List<Source>,
    )

    @Serializable
    data class Source(
        val file: String,
        val label: String? = null,
    )

    private fun videoQualityComparator(): Comparator<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)?.toIntOrNull() ?: 1080
        return compareByDescending<Video> { video ->
            if (extractNumericQuality(video.quality) == preferredQuality) 1 else 0
        }.thenByDescending {
            extractNumericQuality(it.quality)
        }
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

    // Custom pagination
    override fun nextPageUrl(document: Document): String? {
        val moreButton = document.selectFirst("div.more")
        if (moreButton == null) return null

        val nextPage = currentPage + 1
        val isSearch = document.selectFirst("input[name=story]") != null
        val query = if (isSearch) {
            document.selectFirst("input[name=story]")?.attr("value") ?: return null
        } else null

        return if (isSearch && query != null) {
            "$baseUrl/search?story=$query&p=$nextPage"
        } else {
            "$baseUrl/new-video?p=$nextPage"
        }
    }

    // ============================= Utilities ==============================
    private fun animeFromElement(element: Element): SAnime {
        val thumbLink = element.selectFirst("a.item_link")
        val url = thumbLink?.attr("href") ?: ""

        val titleElem = element.selectFirst("div.i_info div.title")
        val title = titleElem?.text()?.trim() ?: "Unknown"

        val img = element.selectFirst("div.i_img img")
        var thumbnail: String? = null
        if (img != null) {
            var src = img.attr("data-src")
            if (src.isBlank()) src = img.attr("src")
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
