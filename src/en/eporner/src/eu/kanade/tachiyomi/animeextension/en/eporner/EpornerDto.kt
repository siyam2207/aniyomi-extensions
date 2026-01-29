package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.Serializable

@Serializable
data class VideoListResponse(
    val videos: List<EpornerVideo>,
    val page: Int,
    val pages: Int,
)

@Serializable
data class EpornerVideo(
    val id: String,
    val title: String,
    val default_thumb: String,
) {
    fun toAnime(): SAnime = SAnime.create().apply {
        url = id
        this.title = this@EpornerVideo.title
        thumbnail_url = default_thumb
    }
}

@Serializable
data class VideoDetailResponse(
    val video: VideoDetail,
)

@Serializable
data class VideoDetail(
    val id: String,
    val title: String,
    val hls: String?,
    val mp4: List<Mp4Video> = emptyList(),
)

@Serializable
data class Mp4Video(
    val quality: String,
    val url: String,
)
