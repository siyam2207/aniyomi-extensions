package eu.kanade.tachiyomi.animeextension.all.eporner

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

internal object EpornerApi {
    private val tag = "EpornerApi"

    fun popularRequest(apiUrl: String, page: Int, headers: Headers): Request =
        GET(
            "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json",
            headers,
        )

    fun latestRequest(apiUrl: String, page: Int, headers: Headers): Request =
        GET(
            "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json",
            headers,
        )

    fun searchRequest(
        apiUrl: String,
        page: Int,
        query: String,
        filters: Any,
        headers: Headers,
    ): Request {
        val parsed = if (filters is eu.kanade.tachiyomi.animesource.model.AnimeFilterList) {
            EpornerFilters.parse(filters)
        } else {
            EpornerFilters.Parsed("all", "0", "0")
        }

        val q = if (query.isBlank()) "all" else URLEncoder.encode(query, "UTF-8")

        val url =
            "$apiUrl/video/search/?query=$q&page=$page&categories=${parsed.category}" +
                "&duration=${parsed.duration}&quality=${parsed.quality}&thumbsize=big&format=json"

        return GET(
            url,
            headers,
        )
    }

    fun parseSearch(json: Json, response: Response): AnimesPage {
        return try {
            val data = json.decodeFromString(ApiSearchResponse.serializer(), response.body.string())
            AnimesPage(
                data.videos.map { it.toSAnime() },
                data.page < data.totalpages,
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse search JSON", e)
            AnimesPage(emptyList(), false)
        }
    }

    fun detailsRequest(apiUrl: String, anime: SAnime, headers: Headers): Request {
        val safeUrl = try {
            anime.url
        } catch (e: kotlin.UninitializedPropertyAccessException) {
            Log.e(tag, "detailsRequest: URL was uninitialized!", e)
            "https://www.eporner.com/"
        }
        val id = safeUrl.substringAfterLast("/").substringBefore("-")
        return GET(
            "$apiUrl/video/id/?id=$id&format=json",
            headers,
        )
    }

    fun parseDetails(json: Json, response: Response): SAnime {
        return try {
            val detail = json.decodeFromString(ApiVideoDetailResponse.serializer(), response.body.string())
            detail.toSAnime()
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse video details from API", e)
            // Create a basic valid SAnime as fallback
            SAnime.create().apply {
                url = response.request.url.toString()
                title = "Error Loading Details"
                status = SAnime.COMPLETED
            }
        }
    }
}
