package eu.kanade.tachiyomi.animeextension.en.hamster

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.regex.Pattern

class Hamster : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Xhamster"
    override val baseUrl = "https://xhamster.com"  // TODO: replace with actual site URL
    override val lang = "en"
    override val supportsLatest = true

    // Custom client with a realistic User‑Agent
    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // TODO: build the correct URL for popular videos
        val url = if (page == 1) "$baseUrl/popular" else "$baseUrl/popular?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "div.video-item" // TODO: adjust selector
    override fun popularAnimeNextPageSelector(): String = "a.next-page" // TODO: adjust
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        // TODO: build URL for latest videos
        val url = if (page == 1) "$baseUrl/new" else "$baseUrl/new?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // TODO: build search URL
        val encodedQuery = query.replace(" ", "+")
        val url = "$baseUrl/search?q=$encodedQuery&page=$page"
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        // TODO: extract title, thumbnail, description, genres
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val genres = "" // TODO: extract categories

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

        // 1) Try to extract from window.playlist (if site uses it)
        val playlistScript = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("window.playlist") }

        if (playlistScript != null) {
            try {
                val playlistJson = extractPlaylistJson(playlistScript)
                val playlist = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }.decodeFromString<Playlist>(playlistJson)

                for (source in playlist.sources) {
                    val videoUrl = source.file
                    if (videoUrl.isNotBlank()) {
                        val quality = source.label?.let { "${it}p" } ?: extractQualityFromUrl(videoUrl)
                        val headers = headersBuilder().add("Referer", baseUrl).build()
                        videos.add(Video(videoUrl, quality, videoUrl, headers))
                    }
                }
                if (videos.isNotEmpty()) return videos.sortedWith(videoQualityComparator())
            } catch (e: Exception) {
                // fall through
            }
        }

        // 2) Fallback: direct <video> tag
        val directVideo = document.select("video").firstOrNull()
        if (directVideo != null) {
            var videoUrl = directVideo.attr("src")
            if (videoUrl.isBlank()) videoUrl = directVideo.attr("data-src")
            if (videoUrl.isNotBlank()) {
                val quality = extractQualityFromUrl(videoUrl)
                val label = if (quality == "2160") "2160p (4K)" else "${quality}p"
                val headers = headersBuilder().add("Referer", baseUrl).build()
                videos.add(Video(videoUrl, label, videoUrl, headers))
                return videos
            }
        }

        // 3) Fallback: <video><source> tags
        val videoSources = document.select("video source")
        for (source in videoSources) {
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                var quality = source.attr("size").takeIf { it.isNotBlank() } ?: ""
                if (quality.isBlank()) quality = extractQualityFromUrl(videoUrl)
                val label = if (quality == "2160") "2160p (4K)" else "${quality}p"
                val headers = headersBuilder().add("Referer", baseUrl).build()
                videos.add(Video(videoUrl, label, videoUrl, headers))
            }
        }

        if (videos.isNotEmpty()) return videos.sortedWith(videoQualityComparator())

        throw Exception("No video sources found")
    }

    private fun extractPlaylistJson(scriptData: String): String {
        val startMarker = "window.playlist ="
        val startIndex = scriptData.indexOf(startMarker)
        if (startIndex == -1) throw Exception("window.playlist not found")

        var braceCount = 0
        var jsonStart = -1
        var jsonEnd = -1
        var i = startIndex + startMarker.length

        while (i < scriptData.length) {
            when (scriptData[i]) {
                '{' -> {
                    if (braceCount == 0) jsonStart = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        jsonEnd = i + 1
                        break
                    }
                }
            }
            i++
        }
        if (jsonStart == -1 || jsonEnd == -1) throw Exception("Could not extract playlist JSON")
        return scriptData.substring(jsonStart, jsonEnd)
    }

    private fun extractQualityFromUrl(url: String): String {
        val pattern = Pattern.compile("[_-](\\d+)p\\.")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else "Unknown"
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

    // ============================= Utilities ==============================
    private fun animeFromElement(element: Element): SAnime {
        // TODO: adjust selectors for thumbnail link, title, image
        val thumbLink = element.selectFirst("a.thumbnail")
        val url = thumbLink?.attr("href") ?: ""
        val titleElem = element.selectFirst("a.title")
        val title = titleElem?.text()?.trim() ?: "Unknown"
        val img = thumbLink?.selectFirst("img")
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
