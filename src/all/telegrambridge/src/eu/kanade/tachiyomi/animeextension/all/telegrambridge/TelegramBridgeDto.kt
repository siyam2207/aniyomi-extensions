package eu.kanade.tachiyomi.animeextension.all.telegrambridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What the bridge's GET /list endpoint returns.

@Serializable
data class BridgeListResponse(
    val items: List<BridgeVideo> = emptyList(),
    @SerialName("next_offset_id") val nextOffsetId: Long? = null,
)

@Serializable
data class BridgeVideo(
    val id: Long,
    val title: String = "",
    val filename: String = "",
    val size: Long = 0,
    val mime: String = "",
    val duration: Double = 0.0,
    val date: String? = null,
)
