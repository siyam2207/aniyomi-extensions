package eu.kanade.tachiyomi.animeextension.en.xmovix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val random = Random()
    private val tag = "MyVidPlayExtractor"

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Headers exactly as in Python script
        val baseHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("Referer", "https://myvidplay.com/")
            .set("DNT", "1")
            .build()

        Log.d(tag, "Fetching embed URL: $url")
        val embedResponse = client.newCall(GET(url, baseHeaders)).execute()
        val html = embedResponse.body.string()
        embedResponse.close()

        // Log a snippet of HTML
        Log.d(tag, "HTML snippet: ${html.take(500)}")

        val token = extractToken(html)
        Log.d(tag, "Extracted token: $token")
        if (token == null) {
            Log.e(tag, "Token not found")
            return emptyList()
        }

        val expiry = extractExpiry(html)
        Log.d(tag, "Extracted expiry: $expiry")
        if (expiry == null) {
            Log.e(tag, "Expiry not found")
            return emptyList()
        }

        val passPath = extractPassPath(html)
        Log.d(tag, "Extracted passPath: $passPath")
        if (passPath == null) {
            Log.e(tag, "pass_md5 path not found")
            return emptyList()
        }

        val passUrl = "https://myvidplay.com$passPath"
        Log.d(tag, "Pass URL: $passUrl")

        val ajaxHeaders = baseHeaders.newBuilder()
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val ajaxResponse = client.newCall(GET(passUrl, ajaxHeaders)).execute()
        val baseVideoUrl = ajaxResponse.body.string().trim()
        ajaxResponse.close()
        Log.d(tag, "Base video URL: $baseVideoUrl")

        if (baseVideoUrl == "RELOAD") {
            Log.e(tag, "Server requested RELOAD")
            return emptyList()
        }

        val suffix = generateRandomString(10)
        val finalUrl = "$baseVideoUrl$suffix?token=$token&expiry=$expiry"
        Log.d(tag, "Final URL: $finalUrl")

        // Prepare video headers – include all original headers + Referer + cookies
        val videoHeaders = baseHeaders.newBuilder()
            .set("Referer", url)
            .apply {
                // Add cookies from client's cookie jar (OkHttp automatically handles them, but we include explicitly)
                val cookies = client.cookieJar.loadForRequest(url.toHttpUrl())
                if (cookies.isNotEmpty()) {
                    val cookieStr = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    set("Cookie", cookieStr)
                    Log.d(tag, "Added cookies: $cookieStr")
                }
            }
            .build()

        // Return as a single video (the URL is a direct mp4, as seen in network logs)
        return listOf(Video(finalUrl, "${prefix}MyVidPlay", finalUrl, videoHeaders))
    }

    private fun extractToken(html: String): String? {
        // Python regex: token=([^&"']+)
        val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: from pass_md5 path (like DoodExtractor)
        val md5Regex = """/pass_md5/([^'"]+)""".toRegex()
        md5Regex.find(html)?.groupValues?.get(1)?.let { path ->
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
        // Python: \.get\('(/pass_md5/[^']+)'
        val passRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
        passRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: "/pass_md5/..." inside quotes
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
