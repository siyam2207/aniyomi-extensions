// Models.kt
package eu.kanade.tachiyomi.extension.all.eporner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiVideoListResponse(
    val count: Int,
    val start: Int,
    @SerialName("per_page") val perPage: Int,
    val page: Int,
    @SerialName("time_ms") val timeMs: Int,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("total_pages") val totalPages: Int,
    val videos: List<ApiVideo>
)

@Serializable
data class ApiVideo(
    val id: String,
    val title: String,
    val keywords: String,
    val views: Int,
    val rate: String,
    val url: String,
    val added: String,
    @SerialName("length_sec") val lengthSec: Int,
    @SerialName("length_min") val lengthMin: String,
    val embed: String,
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    val thumbs: List<ApiThumbnail>
)

@Serializable
data class ApiThumbnail(
    val size: String,
    val width: Int,
    val height: Int,
    val src: String
)

@Serializable
data class ApiVideoDetailResponse(
    val video: ApiVideo
)

// Extension-specific models matching Aniyomi's internal structures
internal data class EpornerVideo(
    val apiVideo: ApiVideo,
    val thumbnailUrls: List<String> = apiVideo.thumbs.map { it.src },
    val previewUrls: List<String> = thumbnailUrls, // For preview feature
    val actors: List<String> = extractActorsFromKeywords(apiVideo.keywords)
) {
    companion object {
        private fun extractActorsFromKeywords(keywords: String): List<String> {
            // Keywords often contain actor names mixed with categories
            // This is a simplistic extraction - could be enhanced
            return keywords.split(", ")
                .filter { !it.contains("hd", ignoreCase = true) }
                .filter { !it.contains("porn", ignoreCase = true) }
                .filter { it.length in 3..25 }
                .distinct()
        }
    }
}
