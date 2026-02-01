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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class PandaMovies : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name: String = "PandaMovies"
    override val baseUrl: String = "https://pandamovies.pw"
    override val lang: String = "all"
    override val supportsLatest: Boolean = true
    override val supportsRelatedAnimes: Boolean = false

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/wp-json/wp/v2/posts?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = response.body?.string().orEmpty()
        val posts = parseJsonToList(json)
        val animeList = posts.map { post ->
            SAnime.create().apply {
                setUrlWithoutDomain(post.link)
                title = post.title.rendered
                description = post.content.rendered
                if (post.featured_media != 0) {
                    thumbnail_url = fetchThumbnail(post.featured_media)
                }
            }
        }
        return AnimesPage(animeList, posts.size == 10)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/wp-json/wp/v2/posts?search=$query&page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body?.string().orEmpty())
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            description = document.selectFirst("div.entry-content")?.text().orEmpty()
            thumbnail_url = document.selectFirst("img.wp-post-image")?.attr("src")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body?.string().orEmpty())
        val episode = SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            setUrlWithoutDomain(document.location())
        }
        return listOf(episode)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body?.string().orEmpty())
        val iframe = document.selectFirst("iframe")
        val videoUrl = iframe?.attr("src").orEmpty()
        return if (videoUrl.isNotEmpty()) listOf(Video(videoUrl, "PandaMovies", videoUrl)) else emptyList()
    }

    override fun List<Video>.sort(): List<Video> = this
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private fun parseJsonToList(json: String): List<WpPost> = try {
        Json { ignoreUnknownKeys = true }.decodeFromString(json)
    } catch (_: Exception) {
        emptyList()
    }

    private fun fetchThumbnail(mediaId: Int): String = try {
        val response = client.newCall(GET("$baseUrl/wp-json/wp/v2/media/$mediaId", headers)).execute()
        val json = response.body?.string().orEmpty()
        Json { ignoreUnknownKeys = true }.decodeFromString<WpMedia>(json).source_url
    } catch (_: Exception) {
        ""
    }

    @Serializable
    data class WpPost(
        val link: String,
        val title: RenderedText,
        val content: RenderedText,
        val featured_media: Int = 0,
    )

    @Serializable
    data class RenderedText(
        val rendered: String,
    )

    @Serializable
    data class WpMedia(
        val source_url: String,
    )
}
