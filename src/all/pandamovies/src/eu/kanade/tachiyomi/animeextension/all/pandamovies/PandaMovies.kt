package eu.kanade.tachiyomi.animeextension.all.pandamovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

class PandaMovies : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name: String = "PandaMovies"
    override val baseUrl: String = "https://pandamovies.pw"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes: Boolean = false

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val body = "action=psy_load_movies&page=$page"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        return POST(
            "$baseUrl/wp-admin/admin-ajax.php",
            headers,
            body,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val html = response.body?.string().orEmpty()
        val document = Jsoup.parse(html)

        val animeList = document.select("div.item").map { element ->
            SAnime.create().apply {
                title = element.selectFirst("h3")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img")?.attr("src")
                setUrlWithoutDomain(
                    element.selectFirst("a")?.attr("href").orEmpty(),
                )
            }
        }

        return AnimesPage(animeList, hasNextPage = true)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request =
        popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ============================== Search (stub for CI) ==============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList
    ): Request = popularAnimeRequest(page)

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body?.string().orEmpty())

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text().orEmpty()
            description = document.selectFirst(".entry-content")?.text(),
        },
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Episode"
                episode_number = 1f
                setUrlWithoutDomain(response.request.url.toString())
            }
        )

    // ============================== Video ==============================

    override fun videoListParse(response: Response): List<Video> =
        emptyList()

    override fun List<Video>.sort(): List<Video> = this

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No preferences yet
    }
}
