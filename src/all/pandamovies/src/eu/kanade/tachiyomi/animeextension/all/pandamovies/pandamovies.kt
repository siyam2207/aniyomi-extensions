package eu.kanade.tachiyomi.animeextension.all.pandamovies

import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PandaMovies : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "PandaMovies"
    override val baseUrl = "https://pandamovies.pw"
    override val lang = "all"
    override val supportsLatest = true

    // ------------------- Popular / Latest -------------------

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/wp-json/wp/v2/posts?per_page=10&page=$page")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonArray = Json.parseToJsonElement(response.body.string()).jsonArray

        val animeList = jsonArray.map { obj ->
            val title = obj.jsonObject["title"]!!.jsonObject["rendered"]!!.jsonPrimitive.content
            val link = obj.jsonObject["link"]!!.jsonPrimitive.content
            val excerpt = obj.jsonObject["excerpt"]!!.jsonObject["rendered"]!!.jsonPrimitive.content
            val classList = obj.jsonObject["class_list"]!!.jsonArray.map { it.toString().replace("\"", "") }

            SAnime.create().apply {
                this.title = Jsoup.parse(title).text()
                setUrlWithoutDomain(link)
                description = Jsoup.parse(excerpt).text()
                genre = classList.filter { it.startsWith("genres-") }.joinToString(", ") { it.replace("genres-", "") }
                author = classList.filter { it.startsWith("casts-") }.joinToString(", ") { it.replace("casts-", "") }
            }
        }

        val hasNextPage = jsonArray.size == 10
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ------------------- Search -------------------

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/wp-json/wp/v2/posts?search=$query&per_page=10&page=$page")
        } else popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ------------------- Episodes -------------------

    override fun episodeListRequest(anime: SAnime) = GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.body.string())
        val iframe = doc.selectFirst("iframe")
        return if (iframe != null) {
            listOf(SEpisode.create().apply {
                name = "Episode 1"
                setUrlWithoutDomain(iframe.attr("src"))
                episode_number = 1f
            })
        } else emptyList()
    }

    // ------------------- Videos -------------------

    override fun videoListRequest(episode: SEpisode) = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = Jsoup.parse(response.body.string())
        val iframe = doc.selectFirst("iframe")
        return if (iframe != null) {
            listOf(Video(iframe.attr("src"), "Embedded", iframe.attr("src")))
        } else emptyList()
    }

    override fun List<Video>.sort(): List<Video> = this
}
