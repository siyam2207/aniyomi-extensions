package eu.kanade.tachiyomi.animeextension.all.xmovix

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.LazyMutable
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Xmovix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Xmovix"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override val client: OkHttpClient = OkHttpClient()

    private var docHeaders by LazyMutable { newHeaders() }
    private var playlistExtractor by LazyMutable { PlaylistUtils(client, docHeaders) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    private fun newHeaders(): Headers = Headers.Builder().apply {
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "en-US,en;q=0.5")
        set("Accept-Encoding", "gzip, deflate, br")
        set("Connection", "keep-alive")
        set("Upgrade-Insecure-Requests", "1")
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
    }.build()

    // =======================
    // Popular / Latest
    // =======================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/most-viewed/?page=$page", docHeaders)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest/?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val list = document.select("div.item").map { element ->
            SAnime.create().apply {
                val a = element.selectFirst("a")!!
                title = a.attr("title").takeIf { it.isNotBlank() } 
                        ?: element.selectFirst("img")?.attr("alt") 
                        ?: "No Title"
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNext = document.selectFirst("a.next") != null
        return AnimesPage(list, hasNext)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =======================
    // Search
    // =======================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("do", "search")
            .addQueryParameter("subaction", "search")
            .addQueryParameter("search_start", page.toString())
            .addQueryParameter("full_search", "0")
            .addQueryParameter("story", query)
            .build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =======================
    // Details
    // =======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            genre = document.select("div.tags a").eachText().joinToString()
            description = document.selectFirst("div.movie-desc p")?.text()
            thumbnail_url = document.selectFirst("img.poster")?.attr("abs:src")
            status = SAnime.COMPLETED
        }
    }

    // =======================
    // Episodes (Players)
    // =======================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(
            GET("$baseUrl${anime.url}", docHeaders),
        ).execute().asJsoup()

        return document.select("a.player-item").mapIndexed { index, element ->
            SEpisode.create().apply {
                name = element.text().trim()
                url = element.attr("href")
                episode_number = (index + 1).toFloat()
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException()

    // =======================
    // Video
    // =======================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Extract Player 2 (DoodStream) - SECOND player link
        val playerLinks = document.select("a.player-item")
        if (playerLinks.size >= 2) {
            val player2Link = playerLinks[1].attr("href") // Index 1 is the second player
            if (player2Link.isNotBlank()) {
                val doodVideos = doodExtractor.videosFromUrl(player2Link, quality = "DoodStream")
                videoList.addAll(doodVideos)
            }
        }

        // Note: Players 1 and 3 are not implemented as requested
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(
            PREF_QUALITY,
            PREF_QUALITY_DEFAULT,
        )!!
        return sortedWith(
            compareByDescending { it.quality.contains(preferred) },
        )
    }

    // =======================
    // Filters / Preferences
    // =======================

    override fun getFilterList() = AnimeFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = PREF_DOMAIN_ENTRIES.toTypedArray()
            entryValues = PREF_DOMAIN_ENTRIES.toTypedArray()
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                baseUrl = newValue as String
                docHeaders = newHeaders()
                playlistExtractor = PlaylistUtils(client, docHeaders)
                true
            }
        }

        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES.toTypedArray()
            entryValues = PREF_QUALITY_VALUES.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }

        screen.addPreference(domainPref)
        screen.addPreference(qualityPref)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf(
            "https://hd.xmovix.net/en",
        )
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRIES.first()

        private const val PREF_QUALITY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf(
            "720p",
            "480p",
            "360p",
        )
        private val PREF_QUALITY_VALUES = listOf(
            "720",
            "480",
            "360",
        )
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()
    }
}
