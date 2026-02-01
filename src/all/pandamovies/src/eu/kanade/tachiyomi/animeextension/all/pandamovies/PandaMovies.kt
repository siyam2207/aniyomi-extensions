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
import okhttp3.Request
import okhttp3.Response

class PandaMovies : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "PandaMovies"
    override val baseUrl = "https://pandamovies.pw"
    override val lang = "all"
    override val supportsLatest = true
    override val supportsRelatedAnimes = false

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/wp-json/wp/v2/posts?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = response.body.string()
        val animes = json.let { parseJsonToList(it) }
        val animeList = animes.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.link)
                title = item.title
                description = item.content
            }
        }
        val hasNextPage = animes.size == 10
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/wp-json/wp/v2/posts?search=$query&page=$page", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

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

    private fun parseJsonToList(json: String): List<WpPost> {
        return try {
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, WpPost::class.java)
            val adapter = moshi.adapter<List<WpPost>>(type)
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class WpPost(
        val link: String,
        val title: String,
        val content: String,
    )
}
