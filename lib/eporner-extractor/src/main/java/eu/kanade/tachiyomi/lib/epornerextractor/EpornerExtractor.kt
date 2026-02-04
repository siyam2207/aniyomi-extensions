package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

class EpornerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val videoIdRegex = Pattern.compile("/embed/([^/]+)/?")
    private val hashRegex = Pattern.compile("hash['\"]?\\s*[:=]\\s*['\"]([a-zA-Z0-9]{20,32})")
    private val m3u8Regex = Pattern.compile("(https?://[^\"']+\\.m3u8[^\"']*)")

    fun videosFromEmbed(embedUrl: String): List<Video> {
        return try {
            // Fetch embed page
            val response = client.newCall(GET(embedUrl, headers)).execute()
            val doc = Jsoup.parse(response.body.string())

            // Extract video ID
            val videoId = extractVideoId(embedUrl)
            if (videoId.isNullOrEmpty()) return emptyList()

            // Extract hash from embed page
            val hash = extractHash(doc)
            if (hash.isNullOrEmpty()) return emptyList()

            // Call XHR API
            val xhrUrl = buildXhrUrl(videoId, hash)
            val xhrHeaders = buildXhrHeaders(embedUrl)
            
            val xhrResponse = client.newCall(GET(xhrUrl, xhrHeaders)).execute()
            val xhrBody = xhrResponse.body?.string() ?: return emptyList()

            // Try to extract HLS first
            val videos = extractHlsVideos(xhrBody, embedUrl)
            if (videos.isNotEmpty()) return videos

            // Fallback to MP4
            extractMp4Videos(xhrBody, embedUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        val matcher = videoIdRegex.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractHash(doc: org.jsoup.nodes.Document): String? {
        val html = doc.html()
        val matcher = hashRegex.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun buildXhrUrl(videoId: String, hash: String): String {
        return "https://www.eporner.com/xhr/video/$videoId" +
                "?hash=$hash" +
                "&domain=www.eporner.com" +
                "&embed=true" +
                "&supportedFormats=hls,mp4" +
                "&_=${System.currentTimeMillis()}"
    }

    private fun buildXhrHeaders(embedUrl: String): Headers {
        return headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .build()
    }

    private fun extractHlsVideos(xhrBody: String, embedUrl: String): List<Video> {
        val matcher = m3u8Regex.matcher(xhrBody)
        if (matcher.find()) {
            val m3u8Url = matcher.group(1)
            return playlistUtils.extractFromHls(
                m3u8Url,
                videoNameGen = { quality -> "Eporner - $quality" }
            )
        }
        return emptyList()
    }

    private fun extractMp4Videos(xhrBody: String, embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        
        // Look for MP4 URLs in the JSON response
        val mp4Pattern = Pattern.compile("\"(https?://[^\"]+\\.mp4[^\"]*)\"")
        val matcher = mp4Pattern.matcher(xhrBody)
        
        while (matcher.find()) {
            val url = matcher.group(1)
            if (url.contains("eporner") && !url.contains("thumb") && !url.contains("preview")) {
                val quality = extractQualityFromUrl(url)
                videos.add(
                    Video(
                        url = url,
                        quality = quality,
                        videoUrl = url,
                        headers = buildVideoHeaders(embedUrl)
                    )
                )
            }
        }
        
        return videos
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Unknown"
        }
    }

    private fun buildVideoHeaders(embedUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", embedUrl)
            .add("Origin", "https://www.eporner.com")
            .add("Accept", "*/*")
            .build()
    }
}
