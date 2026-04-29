package eu.kanade.tachiyomi.animeextension.en.xhamster

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Xhamster : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Xhamster"
    override val baseUrl = "https://xhamster.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36",
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", baseUrl)
                .build()

            chain.proceed(request)
        }
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    // ================= FRONT PAGE (FIXED) =================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/?page=$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String =
        "div.subsection.mono.index-videos.mixed-section > div > div"

    override fun popularAnimeNextPageSelector(): String =
        "a.next, a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime =
        animeFromElement(element)

    // Latest = same as front page fallback
    override fun latestUpdatesRequest(page: Int): Request =
        popularAnimeRequest(page)

    override fun latestUpdatesSelector(): String =
        popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String =
        popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        animeFromElement(element)

    // ================= SEARCH =================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = query.replace(" ", "+")
        return GET("$baseUrl/search?q=$q&page=$page", headers)
    }

    override fun searchAnimeSelector(): String =
        popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String =
        popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime =
        animeFromElement(element)

    // ================= DETAILS =================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst("meta[name=description]")?.attr("content")
            status = SAnime.COMPLETED
        }
    }

    // ================= EPISODE =================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                url = anime.url
            },
        )
    }

    // ================= VIDEO (basic safe fallback) =================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videos = mutableListOf<Video>()

        doc.select("video source").forEach {
            val url = it.attr("src")
            if (url.isNotBlank()) {
                videos.add(Video(url, "Unknown", url))
            }
        }

        if (videos.isEmpty()) {
            throw Exception("No video sources found")
        }

        return videos
    }

    // ================= PARSER =================

    private fun animeFromElement(element: Element): SAnime {
        val link = element.selectFirst("a")
        val img = element.selectFirst("img")

        val url = link?.attr("href") ?: ""

        val title = link?.attr("title")
            ?: img?.attr("alt")
            ?: "Unknown"

        val thumb = img?.attr("data-src")
            ?.ifBlank { img.attr("src") }

        return SAnime.create().apply {
            setUrlWithoutDomain(url)
            this.title = title
            thumbnail_url = thumb
        }
    }

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
