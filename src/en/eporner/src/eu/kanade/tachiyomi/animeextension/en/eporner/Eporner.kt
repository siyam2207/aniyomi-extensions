package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animeextension.AnimeHttpSource
import eu.kanade.tachiyomi.animeextension.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animeextension.anime.*
import eu.kanade.tachiyomi.animeextension.filter.AnimeFilterList
import okhttp3.OkHttpClient
import android.content.Context

class Eporner : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    private lateinit var api: EpornerApi

    override fun client(): OkHttpClient = super.client()

    override fun fetchPopularAnime(page: Int) = api.popularRequest(page)
    override fun fetchLatestUpdates(page: Int) = api.latestRequest(page)
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList) = api.searchRequest(page, query, filters)

    override fun popularAnimeParse(response: okhttp3.Response) = api.parseAnimeList(response)
    override fun latestUpdatesParse(response: okhttp3.Response) = api.parseAnimeList(response)
    override fun searchAnimeParse(response: okhttp3.Response) = api.parseAnimeList(response)

    override fun animeDetailsParse(response: okhttp3.Response): Anime = api.parseDetails(response)
    override fun episodeListParse(response: okhttp3.Response): List<AnimeEpisode> = api.parseEpisodes(response)
    override fun videoListParse(response: okhttp3.Response): List<Video> = api.parseVideos(response)

    override fun getFilterList(): AnimeFilterList = EpornerFilters.getFilters(context)

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Called automatically when filters are applied
        val filters = getFilterList()
        EpornerFilters.saveFilters(context, filters)
    }

    override fun onCreate(context: Context) {
        super.onCreate(context)
        api = EpornerApi(client)
    }
}
