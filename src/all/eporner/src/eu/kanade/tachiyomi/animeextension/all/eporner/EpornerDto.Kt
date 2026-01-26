package eu.kanade.tachiyomi.animeextension.all.eporner.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpornerApiResponse(
    val count: Int,
    val start: Int,
    @SerialName("per_page") val perPage: Int,
    val page: Int,
    @SerialName("time_ms") val timeMs: Int,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("total_pages") val totalPages: Int,
    val videos: List<EpornerVideo>
)

@Serializable
data class EpornerVideo(
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
    @SerialName("default_thumb") val defaultThumb: Thumbnail,
    val thumbs: List<Thumbnail>
)

@Serializable
data class Thumbnail(
    val size: String,
    val width: Int,
    val height: Int,
    val src: String
)

@Serializable
data class EpornerVideoDetail(
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
    @SerialName("default_thumb") val defaultThumb: Thumbnail,
    @SerialName("default_resolution") val defaultResolution: String,
    val thumbs: List<Thumbnail>,
    val models: List<Model>,
    val pornstars: List<Pornstar>
)

@Serializable
data class Model(
    val id: Int,
    val name: String,
    val url: String
)

@Serializable
data class Pornstar(
    val id: Int,
    val name: String,
    val url: String
)
