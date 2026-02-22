package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient) {

    private val random = Random()

    fun videoFromUrl(
        url: String,
        prefix: String = "",
        redirect: Boolean = true,
    ): Video? {
        return runCatching {
            // Step 1: Load embed page with headers matching Python script
            val embedHeaders = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .set("Accept-Language", "en-US,en;q=0.5")
                .set("Referer", "https://myvidplay.com/")
                .set("DNT", "1")
                .build()

            val response = client.newCall(GET(url, embedHeaders)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url
            val host = getBaseUrl(newUrl)
            val html = response.body.string()
            if (!html.contains("/pass_md5/")) return null

            // Step 2: Extract token and expiry from HTML
            val token = extractToken(html) ?: return null
            val expiry = extractExpiry(html) ?: return null

            // Step 3: Find pass_md5 path
            val passPath = extractPassPath(html) ?: return null
            val passUrl = "$host$passPath"

            // Step 4: Call pass_md5 endpoint
            val ajaxHeaders = Headers.Builder()
                .set("User-Agent", embedHeaders["User-Agent"]!!)
                .set("Referer", url)
                .set("X-Requested-With", "XMLHttpRequest")
                .build()

            val passResponse = client.newCall(GET(passUrl, ajaxHeaders)).execute()
            val baseVideoUrl = passResponse.body.string().trim()
            if (baseVideoUrl == "RELOAD") return null

            // Step 5: Generate random suffix
            val randomString = createRandomString(10)

            // Step 6: Build final video URL
            val finalUrl = "$baseVideoUrl$randomString?token=$token&expiry=$expiry"

            // Step 7: Prepare video headers (only Referer and User-Agent are essential)
            val videoHeaders = Headers.Builder()
                .set("User-Agent", embedHeaders["User-Agent"]!!)
                .set("Referer", url)
                .set("Accept", "*/*")
                .build()

            // Determine quality (optional, from page title)
            val quality = extractQuality(html) ?: "MP4"
            val videoName = if (prefix.isNotEmpty()) "$prefix MyVidPlay" else "MyVidPlay"

            Video(finalUrl, "$videoName - $quality", finalUrl, videoHeaders)
        }.getOrNull()
    }

    fun videosFromUrl(url: String, prefix: String = "", redirect: Boolean = true): List<Video> {
        val video = videoFromUrl(url, prefix, redirect)
        return video?.let(::listOf) ?: emptyList()
    }

    private fun extractToken(html: String): String? {
        // token=([^&"']+)
        val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: from pass_md5 path (e.g., /pass_md5/abc123/token)
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
        // .get('/pass_md5/...')
        val passRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
        passRegex.find(html)?.groupValues?.get(1)?.let { return it }

        // Fallback: "/pass_md5/..." inside quotes
        val fallbackRegex = """['"]/pass_md5/([^'"]+)['"]""".toRegex()
        fallbackRegex.find(html)?.groupValues?.get(0)?.let { return it }

        return null
    }

    private fun extractQuality(html: String): String? {
        return Regex("\\d{3,4}p")
            .find(html.substringAfter("<title>").substringBefore("</title>"))
            ?.groupValues
            ?.getOrNull(0)
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun createRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
}
