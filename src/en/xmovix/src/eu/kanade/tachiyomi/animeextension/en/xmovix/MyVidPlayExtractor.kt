package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val random = Random()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // 1. Load embed page with headers matching Python script
        val embedHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("Referer", "https://myvidplay.com/")
            .set("DNT", "1")
            .build()

        val embedResponse = client.newCall(GET(url, embedHeaders)).execute()
        val html = embedResponse.body.string()

        // 2. Extract token and expiry
        val token = extractToken(html) ?: return emptyList()
        val expiry = extractExpiry(html) ?: return emptyList()

        // 3. Find pass_md5 path (like DoodExtractor)
        val passPath = extractPassPath(html) ?: return emptyList()
        val passUrl = "https://myvidplay.com$passPath"

        // 4. Request pass_md5 to get base video URL
        val ajaxHeaders = embedHeaders.newBuilder()
            .set("Referer", url)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val passResponse = client.newCall(GET(passUrl, ajaxHeaders)).execute()
        val baseVideoUrl = passResponse.body.string().trim()
        if (baseVideoUrl == "RELOAD") return emptyList()

        // 5. Generate random suffix (same as DoodExtractor's createHashTable)
        val randomString = generateRandomString(10)

        // 6. Construct final video URL
        val finalUrl = "$baseVideoUrl$randomString?token=$token&expiry=$expiry"

        // 7. Build headers for video request (include Referer and any cookies)
        val videoHeaders = Headers.Builder()
            .set("User-Agent", embedHeaders["User-Agent"]!!)
            .set("Accept", embedHeaders["Accept"]!!)
            .set("Accept-Language", embedHeaders["Accept-Language"]!!)
            .set("Referer", url)
            .set("DNT", "1")
            .build()

        // 8. Return as a single video (the URL is typically a direct stream or HLS playlist)
        return listOf(Video(finalUrl, "${prefix}MyVidPlay", finalUrl, videoHeaders))
    }

    private fun extractToken(html: String): String? {
        // First try: token in URL parameters
        val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Second try: from pass_md5 path (like DoodExtractor)
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
        // Match .get('/pass_md5/...') or "/pass_md5/..." in strings
        val passRegex = """['"]/pass_md5/([^'"]+)['"]""".toRegex()
        passRegex.find(html)?.groupValues?.get(0)?.let { return it }

        // Fallback: direct path in JavaScript
        val directRegex = """/pass_md5/[^'"\s]+""".toRegex()
        directRegex.find(html)?.value?.let { return it }

        return null
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
}
