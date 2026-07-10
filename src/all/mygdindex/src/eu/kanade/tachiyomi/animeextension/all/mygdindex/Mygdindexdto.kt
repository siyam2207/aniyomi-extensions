package eu.kanade.tachiyomi.animeextension.all.mygdindex

import kotlinx.serialization.Serializable

// ---- Response models: what GDI-JS actually sends back ----

@Serializable
data class GDIFile(
    val id: String,
    val driveId: String? = null,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val link: String? = null,
    val rootIdx: Int? = null,
)

@Serializable
data class GDIListData(
    val files: List<GDIFile> = emptyList(),
)

@Serializable
data class GDIListResponse(
    val data: GDIListData,
    val nextPageToken: String? = null,
    val curPageIndex: Int = 0,
)

@Serializable
data class IdToPathResponse(
    val path: String? = null,
)

// ---- Request models: what we send to GDI-JS ----

@Serializable
data class ListRequestBody(
    val page_token: String? = null,
    val page_index: Int = 0,
)

@Serializable
data class SearchRequestBody(
    val q: String,
    val page_token: String? = null,
    val page_index: Int = 0,
)

@Serializable
data class Id2PathRequestBody(
    val id: String,
)

// ---- Internal bookkeeping: what this extension stores in SAnime/SEpisode.url ----
//
// type = "multi"  -> url is a browsable "<domain>/<N>:/path/.../" folder path
// type = "single" -> url is a direct, ready-to-play/download URL (either the
//                     pre-signed "link" field from a listing/search response,
//                     or a plain path URL)
// type = "search" -> url is the RAW opaque encrypted id from a search result;
//                     needs id2path resolution (via idHost/idOrder) before it
//                     can be browsed. This extension never decrypts this
//                     value itself — it's passed straight back to the
//                     server's id2path endpoint as an opaque token.
@Serializable
data class LinkData(
    val type: String,
    val url: String,
    val info: String? = null,
    val fragment: String? = null,
    val idHost: String? = null,
    val idOrder: Int? = null,
)
