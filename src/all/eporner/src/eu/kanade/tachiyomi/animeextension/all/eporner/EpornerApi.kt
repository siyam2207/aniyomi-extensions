package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

object EpornerApi {

    fun popularAnimeRequest(page: Int, headers: Headers, baseUrl: String): Request {
        val url = "$baseUrl/api/v2/video/search/?query=hd&page=$page&order=top-weekly&format=json"
        return GET(url, headers)
    }

    fun latestUpdatesRequest(page: Int, headers: Headers, baseUrl: String): Request {
        val url = "$baseUrl/api/v2/video/search/?query=hd&page=$page&order=latest&format=json"
        return GET(url, headers)
    }

    fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
        headers: Headers,
        baseUrl: String,
    ): Request {
        
        var category = "hd"
        var duration = "0"
        var quality = "0"
        
        filters.forEach { filter ->
            when (filter) {
                is EpornerFilters.CategoryFilter -> category = filter.toUriPart()
                is EpornerFilters.DurationFilter -> duration = filter.toUriPart()
                is EpornerFilters.QualityFilter -> quality = filter.toUriPart()
            }
        }
        
        val finalQuery =
            if (query.isNotBlank())
    URLEncoder.encode(query, "UTF-8")
            else category
        
        val url =
            "$baseUrl/api/v2/video/search/?" +
            "query=$finalQuery" +
            "&page=$page" +
            "&min_duration=$duration" +
            "&quality=$quality" +
            "&format=json"
        
        return GET(url, headers)
    }
    
    fun popularAnimeParse(response: Response, json: Json, tag: String): AnimesPage {
        return try {
            val apiResponse = json.decodeFromString(ApiSearchResponse.serializer(), response.body!!.string())
            val animeList = apiResponse.videos.map { it.toSAnime() }
            AnimesPage(animeList, apiResponse.page < apiResponse.total_pages)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error", e)
            AnimesPage(emptyList(), false)
        }
    }

    fun animeDetailsRequest(anime: SAnime, headers: Headers, baseUrl: String): Request {
        val videoId = anime.url.substringAfterLast("/").substringBefore("-")
        return GET("$baseUrl/api/v2/video/id/?id=$videoId&format=json", headers)
    }

    fun animeDetailsParse(response: Response, json: Json): SAnime {
        val detail = json.decodeFromString(ApiVideoDetailResponse.serializer(), response.body!!.string())
        return detail.toSAnime()
    }

    fun htmlAnimeDetailsParse(response: Response): SAnime {
        return try {
            val doc = response.asJsoup()
            SAnime.create().apply {
                url = response.request.url.toString()
                title = doc.selectFirst("h1")?.text() ?: "Unknown Title"
                thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")
            }
        } catch (_: Exception) {
            SAnime.create().apply { title = "Error loading details" }
        }
    }

    fun videoListParse(response: Response, client: OkHttpClient, headers: Headers): List<Video> {
        return try {
            val doc = response.asJsoup()
            val embedUrl = doc.selectFirst("iframe")?.attr("src") ?: return emptyList()
            val videos = mutableListOf<Video>()
            val embedResponse = client.newCall(GET(embedUrl, headers)).execute()
            val scriptText = embedResponse.asJsoup().select("script").joinToString(" ") { it.html() }
            val mp4Pattern = Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
            mp4Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank()) videos.add(Video(url, "MP4", url, headers))
            }
            videos
        } catch (_: Exception) {
            emptyList()
        }
    }
}
