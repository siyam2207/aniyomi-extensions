package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.*
import eu.kanade.tachiyomi.animesource.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/hd-porn/$page/")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return AnimesPage(emptyList(), false)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hd-porn/$page/?o=latest")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return AnimesPage(emptyList(), false)
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Request {
        return GET("$baseUrl/search/$query/$page/")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return AnimesPage(emptyList(), false)
    }

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        return SAnime.create().apply {
            title = "Eporner Video"
            description = ""
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Episode"
                episode_number = 1f
                url = response.request.url.toString()
            }
        )

    // ============================== Video ================================

    override fun videoListParse(response: Response): List<Video> =
        emptyList()
}
