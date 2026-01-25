package eu.kanade.tachiyomi.animeextension.all.eporner.models

import kotlinx.serialization.Serializable

@Serializable
data class VideoApiResponse(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val duration: Int? = null,
    val views: Long? = null,
    val rating: Double? = null,
    val upload_date: String? = null,
    val thumbnails: Map<String, String>? = null,
    val qualities: Map<String, String>? = null,
    val actors: List<Actor>? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val studio: String? = null
)

@Serializable
data class Actor(
    val id: String? = null,
    val name: String? = null,
    val gender: String? = null,
    val ethnicity: String? = null,
    val measurements: String? = null,
    val thumbnail: String? = null
)

@Serializable
data class VideoStream(
    val quality: String,
    val url: String,
    val type: String, // "mp4", "hls", "embed"
    val size: Long? = null,
    val bitrate: Int? = null
)
