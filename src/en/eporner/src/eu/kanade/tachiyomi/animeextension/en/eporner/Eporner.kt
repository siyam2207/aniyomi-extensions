package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.lang.Exception

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = OkHttpClient()

    // No search implemented here, can add later
    override fun searchAnimeRequest(query: String) = throw NotImplementedError()
    override fun searchAnimeParse(response: Response) = throw NotImplementedError()
    override fun latestUpdatesRequest(page: Int) = throw NotImplementedError()
    override fun latestUpdatesParse(response: Response) = throw NotImplementedError()
    override fun popularAnimeRequest(page: Int) = throw NotImplementedError()
    override fun popularAnimeParse(response: Response) = throw NotImplementedError()
    override fun animeDetailsRequest(animeUrl: String) = throw NotImplementedError()
    override fun animeDetailsParse(response: Response) = throw NotImplementedError()

    override fun videoListRequest(videoUrl: String): Request {
        val videoId = videoUrl.substringAfterLast("-").substringBefore("/")
        val apiUrl = "$baseUrl/api/v2/video/search/?id=$videoId&per_page=1&thumbsize=big"
        return Request.Builder()
            .url(apiUrl)
            .get()
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = JSONObject(response.body?.string().orEmpty())
        val videosArray = json.getJSONArray("videos")
        if (videosArray.length() == 0) return emptyList()

        val videoObj = videosArray.getJSONObject(0)
        val qualities = videoObj.getJSONObject("all_qualities")
        val videoList = mutableListOf<Video>()

        qualities.keys().forEach { quality ->
            val url = qualities.getString(quality)
            videoList.add(Video(url, "${videoObj.getString("title")} [$quality p]", url))
        }

        return videoList.sortedByDescending { it.quality.substringBefore(" p").toIntOrNull() ?: 0 }
    }

    override fun videoListParse(response: Response, page: Int) = videoListParse(response)

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList) =
        throw NotImplementedError()
}
