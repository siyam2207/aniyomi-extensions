package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.util.playlist.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient

class EpornerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    fun videosFromEmbed(embedUrl: String): List<Video> {
        val doc = client.newCall(GET(embedUrl, headers))
            .execute()
            .asJsoup()

        val videoId = embedUrl
            .substringAfter("/embed/")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val hash = Regex("""hash["']?\s*[:=]\s*["']([a-zA-Z0-9]{20,32})""")
            .find(doc.html())
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val xhrUrl =
            "https://www.eporner.com/xhr/video/$videoId" +
            "?hash=$hash&domain=www.eporner.com&embed=true&supportedFormats=hls,mp4"

        val xhrHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = client.newCall(GET(xhrUrl, xhrHeaders))
            .execute()
            .body
            ?.string()
            ?: return emptyList()

        val m3u8 = Regex("""https[^"']+\.m3u8[^"']*""")
            .find(body)
            ?.value
            ?: return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = m3u8,
            referer = embedUrl,
            videoNameGen = { "Eporner - $it" },
        )
    }
}
