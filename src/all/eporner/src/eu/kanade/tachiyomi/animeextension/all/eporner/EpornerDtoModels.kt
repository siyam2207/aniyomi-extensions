package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ==================== SEARCH API RESPONSE ==================== */
// FIXED: Contains ALL root fields from the actual API JSON response.
// This was the main cause of "no results found".
@Serializable
internal data class ApiSearchResponse(
    val count: Int = 0,
    val start: Int = 0,
    @SerialName("per_page") val perPage: Int = 30,
    val page: Int = 1,
    @SerialName("time_ms") val timeMs: Int = 0,
    @SerialName("total_count") val totalCount: Long = 0,
    @SerialName("total_pages") val totalPages: Int = 1,
    val videos: List<ApiVideo> = emptyList(), // The actual list of videos
)

/* ==================== VIDEO OBJECT (for search results) ==================== */
// FIXED: Contains all fields from the 'videos' array in search results.
@Serializable
internal data class ApiVideo(
    val id: String = "",
    val title: String = "",
    val keywords: String = "",
    val url: String = "",
    val views: Long = 0,
    val rate: String = "",
    val added: String = "",
    @SerialName("length_sec") val lengthSec: Int = 0,
    @SerialName("length_min") val lengthMin: String = "",
    val embed: String = "", // CRITICAL: The embed page URL for video extraction
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
    val thumbs: List<ApiThumbnail> = emptyList(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        // ABSOLUTELY GUARANTEE a valid URL for the app to use later
        this.url = when {
            url.isNotBlank() -> url
            id.isNotBlank() -> "https://www.eporner.com/video-$id/"
            else -> "https://www.eporner.com/"
        }
        this.title = if (title.isNotBlank()) title else "Untitled Video"
        this.thumbnail_url = defaultThumb.src
        this.genre = keywords
        status = SAnime.COMPLETED
    }
}

/* ==================== VIDEO DETAIL OBJECT ==================== */
// For the '/api/v2/video/id/' endpoint. Structure is identical to ApiVideo.
@Serializable
internal data class ApiVideoDetailResponse(
    val id: String = "",
    val title: String = "",
    val keywords: String = "",
    val url: String = "",
    val views: Long = 0,
    val rate: String = "",
    val added: String = "",
    @SerialName("length_sec") val lengthSec: Int = 0,
    @SerialName("length_min") val lengthMin: String = "",
    val embed: String = "", // CRITICAL: The embed page URL for video extraction
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
    val thumbs: List<ApiThumbnail> = emptyList(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        this.url = when {
            url.isNotBlank() -> url
            id.isNotBlank() -> "https://www.eporner.com/video-$id/"
            else -> "https://www.eporner.com/"
        }
        this.title = if (title.isNotBlank()) title else "Unknown Title"
        this.thumbnail_url = defaultThumb.src
        this.genre = keywords
        description = "Views: $views | Length: ${lengthSec / 60}min"
        status = SAnime.COMPLETED
    }
}

/* ==================== THUMBNAIL OBJECT ==================== */
@Serializable
internal data class ApiThumbnail(
    val size: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val src: String = "",
)
