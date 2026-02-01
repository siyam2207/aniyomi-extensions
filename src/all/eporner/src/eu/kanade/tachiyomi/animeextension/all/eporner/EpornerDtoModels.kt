package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiSearchResponse(
    @SerialName("videos") val videos: List<ApiVideo>,
    @SerialName("page") val page: Int,
    @SerialName("total_pages") val total_pages: Int
)

@Serializable
data class ApiVideo(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("keywords") val keywords: String,
    @SerialName("url") val url: String,
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        this.url = url
        this.title = title
        this.thumbnail_url = defaultThumb.src
        this.genre = keywords
        this.status = SAnime.COMPLETED
    }
}

@Serializable
data class ApiVideoDetailResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("keywords") val keywords: String,
    @SerialName("views") val views: Long,
    @SerialName("url") val url: String,
    @SerialName("embed") val embed: String,
    @SerialName("default_thumb") val defaultThumb: ApiThumbnail
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        this.url = url
        this.title = title
        this.thumbnail_url = defaultThumb.src
        this.genre = keywords
        this.status = SAnime.COMPLETED
    }
}

@Serializable
data class ApiThumbnail(
    @SerialName("src") val src: String
)
