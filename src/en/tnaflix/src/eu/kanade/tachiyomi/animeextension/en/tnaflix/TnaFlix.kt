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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TnaFlix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "TnaFlix"
    override val baseUrl = "https://www.tnaflix.com"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/featured" else "$baseUrl/featured/$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "div.video-list > div.col-xs-6"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anchor = element.selectFirst("a.thumb, a.video-thumb") ?: element.selectFirst("a")
        val href = anchor?.attr("href") ?: ""
        val title = element.selectFirst(".video-title")?.text() ?: ""
        val thumbnail = anchor?.selectFirst("img")?.attr("abs:data-src")?.ifEmpty {
            anchor.selectFirst("img")?.attr("abs:src")
        } ?: ""

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    override fun popularAnimeNextPageSelector(): String? = "ul.pagination li.pagination-next a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/new" else "$baseUrl/new?page=$page"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("what", query)
            if (page > 1) addQueryParameter("page", page.toString())
        }?.build().toString() ?: "$baseUrl/search?what=$query"
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val title = document.selectFirst("h1")?.text() ?: ""
        val description = document.selectFirst("p.video-detail-description")?.text() ?: ""
        val thumbnail = document.selectFirst("video.player")?.attr("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // Extract tags/categories
        val tags = document.select(".video-detail-badges a.badge-video").eachText().joinToString()
        val artist = document.select(".video-detail-badges a.badge-video-info").firstOrNull()?.text()

        return SAnime.create().apply {
            this.title = title
            this.description = description
            thumbnail_url = thumbnail
            genre = tags
            artist = artist
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())
            }
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoElement = document.selectFirst("video#video-player")
            ?: return emptyList()

        val sources = videoElement.select("source")
        val videos = mutableListOf<Video>()

        for (source in sources) {
            val url = source.attr("src")
            if (url.isBlank()) continue
            val quality = source.attr("size").ifEmpty {
                // Try to extract quality from src filename
                val match = Regex("-(\\d+)p\\.").find(url)
                match?.groupValues?.get(1) ?: "unknown"
            } + "p"
            videos.add(Video(url, quality, url, headers))
        }

        // Fallback: if no sources, try to find m3u8 or other
        if (videos.isEmpty()) {
            val script = document.selectFirst("script:containsData(var hlsUrl)")?.data()
            if (script != null) {
                val hlsUrl = script.substringAfter("var hlsUrl = '").substringBefore("'")
                if (hlsUrl.isNotBlank()) {
                    return listOf(Video(hlsUrl, "HLS", hlsUrl, headers))
                }
            }
        }

        return videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
            setDefaultValue("720")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720") ?: "720"
        return sortedWith(
            compareByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.toIntOrNull() ?: 0 }
        )
    }

    private fun String.toHttpUrlOrNull() = runCatching { okhttp3.HttpUrl.Companion.get(this) }.getOrNull()
}
