package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "$baseUrl/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val tag = "Eporner"

    // ================= HEADERS =================

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0")
        .add("Referer", baseUrl)

    // ================= POPULAR =================

    override fun popularAnimeRequest(page: Int): Request {
        val url =
            "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&format=json"
        return GET(url, headers)
    }

    override fun popularAnimeParse(
        response: Response,
    ): AnimesPage {

        val body = response.body.string()

        val parsed = json.decodeFromString<ApiSearchResponse>(
            body,
        )

        val anime = parsed.videos.map { it.toSAnime() }

        return AnimesPage(
            anime,
            parsed.page < parsed.total_pages,
        )
    }

    // ================= LATEST =================

    override fun latestUpdatesRequest(
        page: Int,
    ) = popularAnimeRequest(page)

    override fun latestUpdatesParse(
        response: Response,
    ) = popularAnimeParse(response)

    // ================= SEARCH =================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList,
    ): Request {

        val q = if (query.isBlank()) {
            "all"
        } else {
            URLEncoder.encode(query, "UTF-8")
        }

        val url =
            "$apiUrl/video/search/?query=$q&page=$page&format=json"

        return GET(url, headers)
    }

    override fun searchAnimeParse(
        response: Response,
    ) = popularAnimeParse(response)

    // ================= DETAILS =================

    override fun animeDetailsParse(
        response: Response,
    ): SAnime {

        val document = Jsoup.parse(
            response.body.string(),
        )

        return SAnime.create().apply {
            title =
                document.selectFirst("h1")?.text()
                    ?: "Eporner Video"

            thumbnail_url =
                document.selectFirst(
                    "meta[property=og:image]",
                )?.attr("content")

            description = "Eporner Video"
            status = SAnime.COMPLETED
        }
    }

    // ================= EPISODES =================

    override fun episodeListParse(
        response: Response,
    ): List<SEpisode> {

        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString()
            },
        )
    }

    // ================= VIDEO =================

    override fun videoListParse(
        response: Response,
    ): List<Video> {

        val html = response.body.string()
        val embedUrl = response.request.url.toString()

        val vid = Regex(
            """EP\.video\.player\.vid\s*=\s*['"]([^'"]+)""",
        ).find(html)?.groupValues?.get(1)

        val hash = Regex(
            """EP\.video\.player\.hash\s*=\s*['"]([^'"]+)""",
        ).find(html)?.groupValues?.get(1)

        if (vid != null && hash != null) {
            fetchViaXhr(embedUrl, vid, hash)
                .let {
                    if (it.isNotEmpty()) return it
                }
        }

        return extractHls(html, embedUrl)
    }

    // ================= XHR =================

    private fun transformHash(
        embedHash: String,
    ): String {

        return embedHash.chunked(8)
            .joinToString("") {
                it.toLong(16).toString(36)
            }
    }

    private fun fetchViaXhr(
        embedUrl: String,
        vid: String,
        embedHash: String,
    ): List<Video> {

        val xhrHash = transformHash(embedHash)

        val url = "$baseUrl/xhr/video/$vid"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("hash", xhrHash)
            ?.addQueryParameter("domain", "www.eporner.com")
            ?.addQueryParameter("_", System.currentTimeMillis().toString())
            ?.build()
            ?.toString()
            ?: return emptyList()

        val res = client.newCall(
            GET(url, headers),
        ).execute()

        if (!res.isSuccessful) return emptyList()

        val parsed =
            json.decodeFromString<XhrVideoResponse>(
                res.body.string(),
            )

        if (!parsed.available) return emptyList()

        val videoHeaders =
            headersBuilder()
                .add("Referer", embedUrl)
                .build()

        val videos = mutableListOf<Video>()

        parsed.sources.hls?.forEach { (_, s) ->
            videos.add(
                Video(
                    s.src,
                    "HLS • Auto",
                    s.src,
                    videoHeaders,
                ),
            )
        }

        parsed.sources.mp4?.forEach { (_, s) ->
            videos.add(
                Video(
                    s.src,
                    "MP4 • ${s.labelShort}",
                    s.src,
                    videoHeaders,
                ),
            )
        }

        return videos
    }

    // ================= HLS FALLBACK =================

    private fun extractHls(
        html: String,
        embedUrl: String,
    ): List<Video> {

        val url = Regex(
            """https://[^"]+master\.m3u8[^"]*""",
        ).find(html)?.value
            ?: return emptyList()

        return listOf(
            Video(
                url,
                "HLS • Auto",
                url,
                headersBuilder()
                    .add("Referer", embedUrl)
                    .build(),
            ),
        )
    }

    // ================= SETTINGS =================

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) = Unit

    // ================= API MODELS =================

    @Serializable
    data class ApiSearchResponse(
        @SerialName("videos")
        val videos: List<ApiVideo>,
        val page: Int,
        val total_pages: Int,
    )

    @Serializable
    data class ApiVideo(
        val title: String,
        val embed: String,
        val keywords: String?,
        val default_thumb: Thumb,
    ) {
        fun toSAnime() = SAnime.create().apply {
            this.title = this@ApiVideo.title
            url = embed
            thumbnail_url = default_thumb.src
            genre = keywords
            status = SAnime.COMPLETED
        }
    }

    @Serializable
    data class Thumb(val src: String)

    // ================= XHR MODELS =================

    @Serializable
    data class XhrVideoResponse(
        val available: Boolean,
        val sources: XhrSources,
    )

    @Serializable
    data class XhrSources(
        val mp4: Map<String, XhrMp4>? = null,
        val hls: Map<String, XhrHls>? = null,
    )

    @Serializable
    data class XhrMp4(
        val labelShort: String,
        val src: String,
    )

    @Serializable
    data class XhrHls(
        val src: String,
    )
}
