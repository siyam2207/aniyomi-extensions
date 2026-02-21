package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Extractor for filmcdm.top embeds.
 *
 * Attempts to find the master playlist URL directly in the page source.
 */
class FilmCdmExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val masterUrl = extractMasterPlaylist(document) ?: return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            videoNameGen = { quality -> "$prefix FilmCdm - $quality" },
        )
    }

    private fun extractMasterPlaylist(document: Document): String? {
        val patterns = listOf(
            // sources: [{file: "https://.../master.m3u8"}]  – braces escaped
            """sources:\s*\[\s*\{\s*file:\s*"([^"]+\.m3u8[^"]*)"\s*\}""".toRegex(),
            // file: "https://.../master.m3u8"
            """file:\s*"([^"]+\.m3u8[^"]*)"""".toRegex(),
            // direct URL
            """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex(),
            // playlist: "https://..."
            """playlist:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
        )

        document.select("script").forEach { script ->
            val scriptHtml = script.html()
            for (pattern in patterns) {
                pattern.find(scriptHtml)?.groupValues?.get(1)?.let { url ->
                    return if (url.startsWith("http")) url else "https:$url"
                }
            }
        }
        return null
    }
}
