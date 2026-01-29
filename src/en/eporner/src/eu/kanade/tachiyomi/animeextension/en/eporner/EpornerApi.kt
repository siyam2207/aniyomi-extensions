package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animeextension.anime.Anime
import eu.kanade.tachiyomi.animeextension.anime.AnimeEpisode
import eu.kanade.tachiyomi.animeextension.anime.AnimesPage
import eu.kanade.tachiyomi.animeextension.anime.Video
import eu.kanade.tachiyomi.animeextension.filter.AnimeFilterList
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class EpornerApi(private val client: OkHttpClient) {

    private val apiBase = "https://www.eporner.com/api/v2"

    // Requests
    fun popularRequest(page: Int): Response = client.newCall(
        Request.Builder()
            .url("$apiBase/videos?sort=top&page=$page")
            .header("User-Agent", "Aniyomi")
            .build()
    ).execute()

    fun latestRequest(page: Int): Response = client.newCall(
        Request.Builder()
            .url("$apiBase/videos?sort=latest&page=$page")
            .header("User-Agent", "Aniyomi")
            .build()
    ).execute()

    fun searchRequest(page: Int, query: String, filters: AnimeFilterList): Response {
        val url = StringBuilder("$apiBase/videos?page=$page")
        if (query.isNotBlank()) url.append("&query=$query")
        EpornerFilters.applyFilters(url, filters)
        return client.newCall(
            Request.Builder()
                .url(url.toString())
                .header("User-Agent", "Aniyomi")
                .build()
        ).execute()
    }

    fun animeDetailsRequest(url: String): Response = client.newCall(
        Request.Builder()
            .url(url)
            .header("User-Agent", "Aniyomi")
            .build()
    ).execute()

    fun episodeListRequest(url: String): Response = animeDetailsRequest(url)
    fun videoListRequest(url: String): Response = animeDetailsRequest(url)

    // Parsing
    fun parseAnimeList(response: Response): AnimesPage {
        val json = JSONObject(response.body!!.string())
        val list = json.getJSONArray("videos")

        val animeList = (0 until list.length()).map {
            val obj = list.getJSONObject(it)
            Anime.create().apply {
                title = obj.getString("title")
                thumbnail_url = obj.getString("default_thumb")
                url = obj.getString("url")
            }
        }
        return AnimesPage(animeList, json.getBoolean("has_more"))
    }

    fun parseDetails(response: Response): Anime {
        val obj = JSONObject(response.body!!.string())
        return Anime.create().apply {
            title = obj.getString("title")
            description = obj.optString("description")
            thumbnail_url = obj.getString("default_thumb")
        }
    }

    fun parseEpisodes(response: Response): List<AnimeEpisode> =
        listOf(
            AnimeEpisode.create().apply {
                name = "Video"
                episode_number = 1f
                url = response.request.url.toString()
            }
        )

    fun parseVideos(response: Response): List<Video> {
        val json = JSONObject(response.body!!.string())
        val sources = json.getJSONArray("sources")
        return (0 until sources.length()).map {
            val src = sources.getJSONObject(it)
            Video(
                src.getString("url"),
                "${src.getInt("quality")}p",
                src.getString("url")
            )
        }
    }
}
