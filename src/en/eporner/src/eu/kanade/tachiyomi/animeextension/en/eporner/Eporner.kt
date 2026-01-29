package eu.kanade.tachiyomi.animeextension.en.eporner

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.animeextension.AnimeHttpSource
import eu.kanade.tachiyomi.animeextension.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animeextension.anime.Anime
import eu.kanade.tachiyomi.animeextension.anime.AnimeEpisode
import eu.kanade.tachiyomi.animeextension.anime.Video
import eu.kanade.tachiyomi.animeextension.filter.AnimeFilterList

class Eporner : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    private lateinit var api: EpornerApi
    private lateinit var context: Context

    override fun client(): OkHttpClient = super.client()

    override fun fetchPopularAnime(page: Int) = api.popularRequest(page)
    override fun fetchLatestUpdates(page: Int) = api.latestRequest(page)
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList) =
        api.searchRequest(page, query, filters)

    override fun popularAnimeParse(response: okhttp3.Response) = api.parseAnimeList(response)
    override fun latestUpdatesParse(response: okhttp3.Response) = api.parseAnimeList(response)
    override fun searchAnimeParse(response: okhttp3.Response) = api.parseAnimeList(response)

    override fun animeDetailsParse(response: okhttp3.Response): Anime = api.parseDetails(response)
    override fun episodeListParse(response: okhttp3.Response): List<AnimeEpisode> = api.parseEpisodes(response)
    override fun videoListParse(response: okhttp3.Response): List<Video> = api.parseVideos(response)

    override fun getFilterList(): AnimeFilterList = EpornerFilters.getFilters(context)

    override fun onCreate(context: Context) {
        super.onCreate(context)
        this.context = context
        api = EpornerApi(client())
    }
}
