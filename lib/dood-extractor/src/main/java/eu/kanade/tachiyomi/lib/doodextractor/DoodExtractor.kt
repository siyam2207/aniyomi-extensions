package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(
        url: String,
        prefix: String? = null,
        redirect: Boolean = true,
        externalSubs: List<Track> = emptyList(),
    ): Video? {
        return runCatching {
            // 1. Fetch the embed page (follow redirects if requested)
            val response = client.newCall(GET(url)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body.string()

            // 2. Check for presence of pass_md5 endpoint (without quotes)
            if (!content.contains("/pass_md5/")) return null

            // 3. Extract token (first from query param, then from path)
            val token = extractToken(content) ?: extractTokenFromPath(content) ?: return null

            // 4. Extract expiry (if missing, use "None" – server accepts it)
            val expiry = extractExpiry(content) ?: "None"

            // 5. Extract the full pass_md5 path
            val passPath = extractPassPath(content) ?: return null
            val passUrl = doodHost + passPath

            // 6. Call pass endpoint with AJAX headers
            val ajaxHeaders = Headers.Builder()
                .add("Referer", newUrl)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val passResponse = client.newCall(GET(passUrl, headers = ajaxHeaders)).execute()
            val baseVideoUrl = passResponse.body.string().trim()

            if (baseVideoUrl == "RELOAD") return null

            // 7. Generate random string and use extracted expiry
            val randomString = createHashTable(10)
            val videoUrl = "$baseVideoUrl$randomString?token=$token&expiry=$expiry"

            // 8. Extract quality from title
            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            // 9. Build quality label (same as before)
            val newQuality = listOfNotNull(
                prefix,
                "Doodstream " + (extractedQuality ?: (if (redirect) "mirror" else ""))
            ).joinToString(" - ")

            // 10. Create video with headers
            Video(
                videoUrl,
                newQuality,
                videoUrl,
                headers = doodHeaders(doodHost),
                subtitleTracks = externalSubs
            )
        }.getOrNull()
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let(::listOf) ?: emptyList()
    }

    // ---------- Helper methods (adapted from Python version) ----------

    private fun extractToken(html: String): String? {
        // token=... (query parameter)
        val tokenRegex = Regex("""token=([^&'"\s]+)""")
        return tokenRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractTokenFromPath(html: String): String? {
        // Try .get('/pass_md5/...')
        val pathRegex = Regex("""\.get\('(/pass_md5/[^']+)'""")
        pathRegex.find(html)?.groupValues?.get(1)?.let { fullPath ->
            val parts = fullPath.split('/')
            if (parts.size >= 3) return parts.last()
        }
        // Fallback: "/pass_md5/..." inside quotes
        val fallbackRegex = Regex("""['"]/pass_md5/([^'"]+)['"]""")
        fallbackRegex.find(html)?.groupValues?.get(1)?.let { fullPath ->
            val parts = fullPath.split('/')
            if (parts.size >= 3) return parts.last()
        }
        return null
    }

    private fun extractExpiry(html: String): String? {
        // expiry=... (query parameter)
        val expiryRegex = Regex("""expiry=(\d+)""")
        expiryRegex.find(html)?.groupValues?.get(1)?.let { return it }
        // JSON key: "expiry": 123456
        val jsonExpiry = Regex(""""expiry":\s*(\d+)""")
        jsonExpiry.find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun extractPassPath(html: String): String? {
        // Try .get('/pass_md5/...')
        val pathRegex = Regex("""\.get\('(/pass_md5/[^']+)'""")
        pathRegex.find(html)?.groupValues?.get(1)?.let { return it }
        // Fallback: "/pass_md5/..." inside quotes
        val fallbackRegex = Regex("""['"]/pass_md5/([^'"]+)['"]""")
        fallbackRegex.find(html)?.groupValues?.get(0)?.let { return it }
        return null
    }

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()
}
