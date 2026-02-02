package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    internal val apiUrl = "$baseUrl/api/v2"
    internal val json: Json by injectLazy()
    internal val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Accept", "application/json, text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ===== Popular / Latest / Search =====
    override fun popularAnimeRequest(page: Int): Request =
        EpornerApi.popularRequest(apiUrl, page, headers)

    override fun popularAnimeParse(response: Response) =
        EpornerApi.parseSearch(json, response)

    override fun latestUpdatesRequest(page: Int): Request =
        EpornerApi.latestRequest(apiUrl, page, headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        EpornerApi.searchRequest(apiUrl, page, query, filters, headers)

    override fun searchAnimeParse(response: Response) =
        EpornerApi.parseSearch(json, response)

    // ===== Details =====
    override fun animeDetailsRequest(anime: SAnime): Request =
        EpornerApi.detailsRequest(apiUrl, anime, headers)

    override fun animeDetailsParse(response: Response): SAnime =
        EpornerApi.parseDetails(json, response)

    // ===== Episodes =====
    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(SEpisode.create().apply {
            name = "Video"
            episode_number = 1F
            url = response.request.url.toString()
        })

    // ===== Videos =====
    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> =
        EpornerVideoExtractor(client, headers, preferences)
            .extract(response)

    // ===== Preferences =====
    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        EpornerPreferences.setup(screen, preferences)

    override fun getFilterList() = EpornerFilters.filterList()
}
