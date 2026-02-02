package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ApiSearchResponse(
    val videos: List<ApiVideo>,
    val page: Int,
    val total_pages: Int,
)

@Serializable
internal data class ApiVideo(
    val id: String = "",
    val title: String = "",
    val keywords: String = "",
    val url: String = "",
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        // ABSOLUTELY GUARANTEE the URL is set
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

@Serializable
internal data class ApiVideoDetailResponse(
    val id: String = "",
    val title: String = "",
    val keywords: String = "",
    val views: Long = 0,
    val url: String = "",
    @SerialName("length_sec") val lengthSec: Int = 0,
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail = ApiThumbnail(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        // ABSOLUTELY GUARANTEE the URL is set
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

@Serializable
internal data class ApiThumbnail(
    val src: String = "",
    val width: Int = 0,
    val height: Int = 0,
)
