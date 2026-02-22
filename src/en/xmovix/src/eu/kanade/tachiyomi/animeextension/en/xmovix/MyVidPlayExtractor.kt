package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val random = Random()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Base headers that match the Python script exactly
        val baseHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("Referer", "https://myvidplay.com/")
            .set("DNT", "1")
            .build()

        // Step 1: Load the embed page
        val document = client.newCall(GET(url, baseHeaders)).execute().asJsoup()
        val html = document.html()

        // Step 2: Extract token and expiry
        val token = extractToken(html) ?: return emptyList()
        val expiry = extractExpiry(html) ?: return emptyList()

        // Step 3: Find pass_md5 endpoint and call it
        val passPath = extractPassPath(html) ?: return emptyList()
        val passUrl = "https://myvidplay.com$passPath"

        val ajaxHeaders = baseHeaders.newBuilder()
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

        // Step 6: Prepare video headers (same as Python streamer – only Referer)
        val videoHeaders = Headers.Builder()
            .set("Referer", url)
            .build()

        // Use a fresh PlaylistUtils with the same client and these videoHeaders
        val playlistUtils = PlaylistUtils(client, videoHeaders)

        return if (finalUrl.contains(".m3u8")) {
            // Extract HLS with consistent headers for all requests
            playlistUtils.extractFromHls(
                playlistUrl = finalUrl,
                referer = url,
                masterHeadersGen = { _, _ -> videoHeaders },
                videoHeadersGen = { _, _, _ -> videoHeaders },
                videoNameGen = { quality -> "${prefix}MyVidPlay - $quality" },
            )
        } else {
            listOf(Video(finalUrl, "${prefix}MyVidPlay", finalUrl, videoHeaders))
        }
    }

    private fun extractToken(html: String): String? {
        val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)?.let { return it }

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
        val passRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
        passRegex.find(html)?.groupValues?.get(1)?.let { return it }

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
