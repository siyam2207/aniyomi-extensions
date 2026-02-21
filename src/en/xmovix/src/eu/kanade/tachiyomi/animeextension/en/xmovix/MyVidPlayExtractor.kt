package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Random

/**
 * Extractor for myvidplay.com embeds.
 *
 * Based on the Python downloader logic:
 * 1. Fetch embed page, extract token and expiry.
 * 2. Find the pass_md5 endpoint and call it to get base video URL.
 * 3. Append random suffix, token, and expiry to construct final URL.
 */
class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val random = Random()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Step 1: Load the embed page
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        val html = document.html()

        // Step 2: Extract token and expiry
        val token = extractToken(html) ?: return emptyList()
        val expiry = extractExpiry(html) ?: return emptyList()

        // Step 3: Find pass_md5 endpoint and call it
        val passPath = extractPassPath(html) ?: return emptyList()
        val passUrl = "https://myvidplay.com$passPath"

        val ajaxHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val baseVideoUrl = client.newCall(GET(passUrl, ajaxHeaders))
            .execute()
            .body
            .string()
            .trim()

        if (baseVideoUrl == "RELOAD") {
            return emptyList()
        }

        // Step 4: Generate random suffix (10 chars)
        val suffix = generateRandomString(10)

        // Step 5: Build final URL
        val finalUrl = "$baseVideoUrl$suffix?token=$token&expiry=$expiry"

        // Step 6: Return as video (or HLS playlist)
        return if (finalUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = finalUrl,
                referer = url,
                videoNameGen = { quality -> "${prefix}MyVidPlay - $quality" },
            )
        } else {
            listOf(Video(finalUrl, "${prefix}MyVidPlay", finalUrl, headers))
        }
    }

    private fun extractToken(html: String): String? {
        // Look for token in URL parameters (e.g., ?token=...)
        val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: inside pass_md5 path (e.g., /pass_md5/abc123/token)
        val passMd5Regex = """\.get\('/pass_md5/([^']+)'""".toRegex()
        passMd5Regex.find(html)?.groupValues?.get(1)?.let { path ->
            val parts = path.split('/')
            if (parts.size >= 2) return parts[1]
        }
        return null
    }

    private fun extractExpiry(html: String): String? {
        val expiryRegex = """expiry=(\d+)""".toRegex()
        return expiryRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractPassPath(html: String): String? {
        // e.g., .get('/pass_md5/abc123')
        val passRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
        passRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: "/pass_md5/abc123" inside a string
        val fallbackRegex = """['"]/pass_md5/([^'"]+)['"]""".toRegex()
        fallbackRegex.find(html)?.groupValues?.get(0)?.let { return it }
        return null
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
}
