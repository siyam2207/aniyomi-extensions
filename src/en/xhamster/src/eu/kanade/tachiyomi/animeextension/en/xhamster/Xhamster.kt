package eu.kanade.tachiyomi.animeextension.en.hamster

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.regex.Pattern

class Hamster : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Xhamster"
    override val baseUrl = "https://xhamster.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ================= POPULAR =================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular" else "$baseUrl/popular?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String =
        "div.subsection.mono.index-videos.mixed-section > div > div"

    override fun popularAnimeNextPageSelector(): String =
        "a.next, a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime =
        animeFromElement(element)

    // ================= LATEST =================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/new" else "$baseUrl/new?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime =
        animeFromElement(element)

    // ================= SEARCH =================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = query.replace(" ", "+")
        val url = "$baseUrl/search?q=$q&page=$page"
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element): SAnime =
        animeFromElement(element)

    // ================= DETAILS =================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Unknown"
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[name=description]")?.attr("content")
            genre = document.select("a[href*='/tags/']").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
        }
    }

    // ================= EPISODES =================
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

    // ================= VIDEOS =================
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Try script playlist
        val script = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("playlist") }

        if (script != null) {
            try {
                val json = extractJson(script)
                val playlist = Json { ignoreUnknownKeys = true }
                    .decodeFromString<Playlist>(json)

                playlist.sources.forEach {
                    if (it.file.isNotBlank()) {
                        val quality = it.label ?: extractQuality(it.file)
                        videos.add(Video(it.file, quality, it.file))
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback <video><source>
        document.select("video source").forEach {
            val url = it.attr("src")
            if (url.isNotBlank()) {
                val quality = extractQuality(url)
                videos.add(Video(url, quality, url))
            }
        }

        if (videos.isEmpty()) throw Exception("No video sources found")

        return videos.sortedByDescending { extractQualityNum(it.quality) }
    }

    // ================= PARSER =================
    private fun animeFromElement(element: Element): SAnime {
        val link = element.selectFirst("a")
        val img = element.selectFirst("img")

        val url = link?.attr("href") ?: ""

        val title = link?.attr("title")?.trim()
            ?: img?.attr("alt")?.trim()
            ?: "Unknown"

        var thumbnail: String? = null
        if (img != null) {
            var src = img.attr("data-src")
            if (src.isBlank()) src = img.attr("src")

            if (src.isNotBlank()) {
                thumbnail = if (src.startsWith("http")) src else "$baseUrl$src"
            }
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    private fun extractJson(script: String): String {
        val start = script.indexOf("{")
        val end = script.lastIndexOf("}") + 1
        return script.substring(start, end)
    }

    private fun extractQuality(url: String): String {
        val m = Pattern.compile("(\\d{3,4})p").matcher(url)
        return if (m.find()) m.group(1) + "p" else "Unknown"
    }

    private fun extractQualityNum(q: String): Int {
        return q.filter { it.isDigit() }.toIntOrNull() ?: 0
    }

    @Serializable
    data class Playlist(val sources: List<Source>)

    @Serializable
    data class Source(val file: String, val label: String? = null)

    // ================= SETTINGS =================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080", "720", "480")
            setDefaultValue("1080")
            summary = "%s"
        }.also(screen::addPreference)
    }
}
