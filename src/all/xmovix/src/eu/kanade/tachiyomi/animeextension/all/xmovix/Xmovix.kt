package eu.kanade.tachiyomi.animeextension.all.xmovix

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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import extensions.utils.LazyMutable
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy

class Xmovix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Xmovix"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private var docHeaders by LazyMutable { newHeaders() }
    private var playlistExtractor by LazyMutable { PlaylistUtils(client, docHeaders) }

    override val client: OkHttpClient = OkHttpClient()

    private fun newHeaders(): Headers = Headers.Builder().apply {
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
    }.build()

    // --- Popular Anime ---
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/popular?page=$page", docHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val entries = document.select("div.movie-card").map { element ->
            SAnime.create().apply {
                val a = element.selectFirst("a")!!
                setUrlWithoutDomain(a.attr("href"))
                title = a.attr("title")
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }
        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(entries, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest?page=$page", docHeaders)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // --- Search ---
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search?q=$query&page=$page"
        return GET(url, docHeaders)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // --- Anime Details ---
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")!!.text()
            genre = document.select("a.genre").eachText().joinToString()
            description = document.selectFirst("div.description")?.text()
            thumbnail_url = document.selectFirst("img.cover")?.attr("abs:src")
            status = SAnime.COMPLETED
        }
    }

    // --- Episodes ---
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET("$baseUrl${anime.url}", docHeaders)).execute().asJsoup()
        return document.select("div.episode-card").map {
            SEpisode.create().apply {
                url = it.selectFirst("a")!!.attr("href")
                name = it.selectFirst("a")!!.text()
                episode_number = name.filter { c -> c.isDigit() }.toFloatOrNull() ?: 1f
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // --- Videos ---
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let(Unpacker::unpack) ?: return emptyList()
        val masterUrl = scriptData.substringAfter("source=\"").substringBefore("\";")
        return playlistExtractor.extractFromHls(masterUrl, referer = "$baseUrl/")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun getFilterList() = AnimeFilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = PREF_DOMAIN_ENTRIES,
            entryValues = PREF_DOMAIN_ENTRIES,
            default = PREF_DOMAIN_DEFAULT,
        ) { baseUrl = it }

        screen.addListPreference(
            key = PREF_QUALITY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_ENTRIES = listOf("https://xmovix.com")
        private val PREF_DOMAIN_DEFAULT = PREF_DOMAIN_ENTRIES.first()

        private const val PREF_QUALITY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()
    }
}
