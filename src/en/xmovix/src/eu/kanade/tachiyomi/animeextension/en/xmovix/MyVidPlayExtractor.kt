package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Random

class MyVidPlayExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val random = Random()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // Use a custom headers builder that matches the Python script
        val customHeaders = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("Referer", "https://myvidplay.com/")
            .set("DNT", "1")
            .build()

        val document = client.newCall(GET(url, customHeaders)).execute().asJsoup()
        val html = document.html()

        val token = extractToken(html) ?: return emptyList()
        val expiry = extractExpiry(html) ?: return emptyList()

        val passPath = extractPassPath(html) ?: return emptyList()
        val passUrl = "https://myvidplay.com$passPath"

        val ajaxHeaders = customHeaders.newBuilder()
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

        val suffix = generateRandomString(10)
        val finalUrl = "$baseVideoUrl$suffix?token=$token&expiry=$expiry"

        return if (finalUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = finalUrl,
                referer = url,
                videoNameGen = { quality -> "${prefix}MyVidPlay - $quality" },
            )
        } else {
            listOf(Video(finalUrl, "${prefix}MyVidPlay", finalUrl, customHeaders))
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
