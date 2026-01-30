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
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.LazyMutable
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
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

    private fun newHeaders(): Headers = Headers.Builder().apply {
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
    }.build()

    // =======================
    // Popular
    // =======================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/popular?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val list = document.select("div.movie-card").map { element ->
            SAnime.create().apply {
                val a = element.selectFirst("a")!!
                title = a.attr("title")
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }
        val hasNext = document.selectFirst("a.next") != null
        return AnimesPage(list, hasNext)
    }

    // =======================
    // Latest
    // =======================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest?page=$page", docHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =======================
    // Search
    // =======================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request =
        GET("$baseUrl/search?q=$query&page=$page", docHeaders)

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =======================
    // Details
    // =======================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            genre = document.select("a.genre").eachText().joinToString()
            description = document.selectFirst("div.description")?.text()
            thumbnail_url = document.selectFirst("img.cover")?.attr("abs:src")
            status = SAnime.COMPLETED
        }
    }

    // =======================
    // Episodes
    // =======================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(
            GET("$baseUrl${anime.url}", docHeaders),
        ).execute().asJsoup()

        return document.select("div.episode-card").map { element ->
            SEpisode.create().apply {
                val a = element.selectFirst("a")!!
                name = a.text()
                url = a.attr("href")
                episode_number =
                    name.filter { it.isDigit() }.toFloatOrNull() ?: 1f
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

        val unpacked = document
            .selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let(Unpacker::unpack)
            ?: return emptyList()

        val masterUrl = unpacked
            .substringAfter("source=\"")
            .substringBefore("\"")

        return playlistExtractor.extractFromHls(
            masterUrl,
            referer = "$baseUrl/",
        )
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
            "https://xmovix.net/en",
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
