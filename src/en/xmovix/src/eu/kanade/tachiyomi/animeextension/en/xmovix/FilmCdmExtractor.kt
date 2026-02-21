package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

/**
 * Extractor for filmcdm.top embeds.
 *
 * Attempts to find the master playlist URL directly in the page source.
 * If that fails (e.g., dynamic loading), the extractor returns empty list,
 * and the main source can fall back to other methods (like UniversalExtractor).
 */
class FilmCdmExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Step 1: Load embed page
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val html = document.html()

        // Step 2: Look for a master playlist URL in script tags
        val masterUrl = extractMasterPlaylist(html) ?: return emptyList()

        // Step 3: Use PlaylistUtils to extract all qualities
        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            videoNameGen = { quality -> "${prefix}FilmCdm - $quality" }
        )
    }

    private fun extractMasterPlaylist(html: String): String? {
        // Common patterns for HLS playlists
        val patterns = listOf(
            // e.g., sources: [{file: "https://.../master.m3u8"}]
            """sources:\s*\[\s*{\s*file:\s*"([^"]+\.m3u8[^"]*)"\s*}""".toRegex(),
            // e.g., file: "https://.../master.m3u8"
            """file:\s*"([^"]+\.m3u8[^"]*)"""".toRegex(),
            // Direct .m3u8 URL in a string
            """(https?://[^"'\s]+\.m3u8[^"'\s]*)""".toRegex(),
            // Player setup with playlist URL
            """playlist: ["']([^"']+\.m3u8[^"']*)"""".toRegex()
        )

        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.get(1)?.let { url ->
                // Ensure it's an absolute URL
                return if (url.startsWith("http")) url else "https:${url}"
            }
        }

        // If not found in the whole HTML, try looking inside <script> tags specifically
        val document = org.jsoup.Jsoup.parse(html)
        document.select("script").forEach { script ->
            val scriptHtml = script.html()
            for (pattern in patterns) {
                pattern.find(scriptHtml)?.groupValues?.get(1)?.let { url ->
                    return if (url.startsWith("http")) url else "https:${url}"
                }
            }
        }

        return null
    }
}
