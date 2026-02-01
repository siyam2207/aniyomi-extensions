package eu.kanade.tachiyomi.animeextension.all.pandamovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class PandaMovies : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name: String = "PandaMovies"
    override val baseUrl: String = "https://pandamovies.pw"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes: Boolean = false

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/wp-json/wp/v2/posts?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = response.body.string()
        val animes = parseJsonToList(json)
        val animeList = animes.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.link)
                title = item.title.rendered
                description = item.content.rendered
            }
        }
        val hasNextPage = animes.size == 10
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return popularAnimeRequest(page)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/wp-json/wp/v2/posts?search=$query&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            description = document.selectFirst("div.entry-content")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("src")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            setUrlWithoutDomain(document.location())
        }
        return listOf(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe")
        val videoUrl = iframe?.attr("src").orEmpty()
        return if (videoUrl.isNotEmpty()) {
            listOf(Video(videoUrl, "PandaMovies", videoUrl))
        } else {
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> = this

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No preferences for now
    }

    // Helper to parse JSON from WP REST API
    private fun parseJsonToList(json: String): List<WpPost> {
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Serializable
    data class WpPost(
        val link: String,
        val title: RenderedText,
        val content: RenderedText,
    )

    @Serializable
    data class RenderedText(
        val rendered: String,
    )
}
