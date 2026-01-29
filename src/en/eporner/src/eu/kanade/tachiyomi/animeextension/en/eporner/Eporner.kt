package eu.kanade.tachiyomi.animeextension.en.eporner

import android.app.Application
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    private val client: OkHttpClient by injectLazy()

    override fun popularAnimeRequest(page: Int): okhttp3.Request {
        return GET("$baseUrl/json/categories/popular/$page/")
    }

    override fun popularAnimeParse(response: okhttp3.Response): AnimesPage {
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        val animeList = json["videos"]!!.jsonArray.map { obj ->
            SAnime.create().apply {
                title = obj.jsonObject["title"]!!.jsonPrimitive.content
                setUrlWithoutDomain("/videos/${obj.jsonObject["id"]!!.jsonPrimitive.content}")
                thumbnail_url = obj.jsonObject["thumbnail_url"]?.jsonPrimitive?.content ?: ""
            }
        }
        val hasNextPage = json["has_next"]?.jsonPrimitive?.boolean ?: false
        return AnimesPage(
            animeList,
            hasNextPage,
        )
    }

    override fun latestUpdatesRequest(page: Int): okhttp3.Request {
        return GET("$baseUrl/json/categories/latest/$page/")
    }

    override fun latestUpdatesParse(response: okhttp3.Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): okhttp3.Request {
        return GET("$baseUrl/json/search/$query/$page/")
    }

    override fun searchAnimeParse(response: okhttp3.Response) = popularAnimeParse(response)

    override fun videoListParse(response: okhttp3.Response): List<Video> {
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return listOf(
            Video(
                url = json["video_url"]!!.jsonPrimitive.content,
                quality = "HD",
                video_type = "mp4",
            ),
        )
    }

    override fun episodeListParse(response: okhttp3.Response): List<SEpisode> {
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return listOf(
            SEpisode.create().apply {
                name = json["title"]!!.jsonPrimitive.content
                setUrlWithoutDomain("/videos/${json["id"]!!.jsonPrimitive.content}")
            },
        )
    }
}
