package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "EpornerExtension"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json, text/html, */*")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Origin", baseUrl)
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    // Popular / Latest
    override fun popularAnimeRequest(page: Int): Request =
        EpornerApi.popularAnimeRequest(page, headersBuilder().build(), baseUrl)

    override fun popularAnimeParse(response: Response) =
        EpornerApi.popularAnimeParse(response, json, tag)

    override fun latestUpdatesRequest(page: Int): Request =
        EpornerApi.latestUpdatesRequest(page, headersBuilder().build(), baseUrl)

    override fun latestUpdatesParse(response: Response) =
        popularAnimeParse(response)

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        EpornerApi.searchAnimeRequest(page, query, filters, headersBuilder().build(), baseUrl)

    override fun searchAnimeParse(response: Response) =
        popularAnimeParse(response)

    // Anime Details
    override fun animeDetailsRequest(anime: SAnime): Request =
        EpornerApi.animeDetailsRequest(anime, headersBuilder().build(), baseUrl)

    override fun animeDetailsParse(response: Response): SAnime =
        try {
            EpornerApi.animeDetailsParse(response, json)
        } catch (e: Exception) {
            Log.w(tag, "API details failed: ${e.message}")
            EpornerApi.htmlAnimeDetailsParse(response)
        }

    // Episodes
    override fun episodeListRequest(anime: SAnime): Request =
        GET(anime.url, headersBuilder().build())

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString()
            },
        )

    // Videos
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headersBuilder().build())

    override fun videoListParse(response: Response): List<Video> =
        EpornerApi.videoListParse(response, client, headersBuilder().build())

    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""

    // Filters
    override fun getFilterList(): AnimeFilterList = EpornerFilters.filterList

    // Preferences required by ConfigurableAnimeSource
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No custom preferences yet
    }
}
