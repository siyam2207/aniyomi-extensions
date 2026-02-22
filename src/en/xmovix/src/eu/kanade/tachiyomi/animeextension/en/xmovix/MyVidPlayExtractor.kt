package eu.kanade.tachiyomi.animeextension.en.xmovix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val random = Random()
    private val tag = "MyVidPlayExtractor"

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // 1. Headers that exactly match the Python script
        val baseHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("Referer", "https://myvidplay.com/")
            .set("DNT", "1")
            .build()

        // 2. Load embed page
        val embedResponse = client.newCall(GET(url, baseHeaders)).execute()
        val html = embedResponse.body.string()
        val document = org.jsoup.Jsoup.parse(html)

        // Optional: log cookies received
        embedResponse.header("set-cookie")?.let { Log.d(tag, "Cookies from embed: $it") }

        // 3. Extract token and expiry
        val token = extractToken(html) ?: run {
            Log.e(tag, "Token not found")
            return emptyList()
        }
        val expiry = extractExpiry(html) ?: run {
            Log.e(tag, "Expiry not found")
            return emptyList()
        }
        Log.d(tag, "Token: $token, Expiry: $expiry")

        // 4. Find pass_md5 endpoint
        val passPath = extractPassPath(html) ?: run {
            Log.e(tag, "pass_md5 path not found")
            return emptyList()
        }
        val passUrl = "https://myvidplay.com$passPath"
        Log.d(tag, "passUrl: $passUrl")

        // 5. AJAX request to get base video URL (same headers as Python)
        val ajaxHeaders = baseHeaders.newBuilder()
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val ajaxResponse = client.newCall(GET(passUrl, ajaxHeaders)).execute()
        val baseVideoUrl = ajaxResponse.body.string().trim()
        Log.d(tag, "Base video URL: $baseVideoUrl")
        if (baseVideoUrl == "RELOAD") {
            Log.e(tag, "Server requested RELOAD")
            return emptyList()
        }

        // 6. Generate random suffix
        val suffix = generateRandomString(10)
        val finalUrl = "$baseVideoUrl$suffix?token=$token&expiry=$expiry"
        Log.d(tag, "Final URL: $finalUrl")

        // 7. Prepare headers for video segments (include all original headers + Referer)
        //    Also try to extract cookies from the client's cookie jar
        val cookieJar = client.cookieJar
        val cookies = cookieJar.loadForRequest(url.toHttpUrl())
        val cookieHeader = if (cookies.isNotEmpty()) {
            cookies.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
        Log.d(tag, "Cookies from jar: $cookieHeader")

        val videoHeaders = baseHeaders.newBuilder()
            .set("Referer", url)
            .apply {
                if (cookieHeader.isNotBlank()) {
                    set("Cookie", cookieHeader)
                }
            }
            .build()

        // 8. Use PlaylistUtils with the same headers for all segment requests
        val playlistUtils = PlaylistUtils(client, videoHeaders)
        return if (finalUrl.contains(".m3u8")) {
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
