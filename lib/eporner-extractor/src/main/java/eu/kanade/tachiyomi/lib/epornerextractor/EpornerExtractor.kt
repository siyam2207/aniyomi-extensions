package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

class EpornerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val videoIdPattern = Pattern.compile("/embed/([^/]+)/?")
    private val hashPattern = Pattern.compile("hash['\"]?\\s*[:=]\\s*['\"]([a-zA-Z0-9]{20,32})")

    fun videosFromEmbed(embedUrl: String): List<Video> {
        return try {
            var attempts = 0
            val maxAttempts = 2
            var videos: List<Video> = emptyList()

            while (attempts < maxAttempts && videos.isEmpty()) {
                videos = tryExtractVideos(embedUrl)
                attempts++
                if (videos.isEmpty()) {
                    // Wait a bit before retry
                    Thread.sleep(500)
                }
            }
            
            // If no HLS found, return empty - DO NOT FALLBACK TO MP4!
            videos
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun tryExtractVideos(embedUrl: String): List<Video> {
        return try {
            // Get video ID from URL
            val videoId = extractVideoId(embedUrl) ?: return emptyList()
            
            // Fetch embed page to get hash
            val embedRequest = Request.Builder()
                .url(embedUrl)
                .headers(headers)
                .build()
            
            val embedResponse = client.newCall(embedRequest).execute()
            val embedHtml = embedResponse.body?.string() ?: return emptyList()
            
            // Extract hash from HTML
            val hash = extractHash(embedHtml) ?: return emptyList()
            
            // Build XHR URL
            val xhrUrl = "https://www.eporner.com/xhr/video/$videoId" +
                "?hash=$hash" +
                "&domain=www.eporner.com" +
                "&embed=true" +
                "&supportedFormats=hls" + // IMPORTANT: Only request HLS
                "&_=${System.currentTimeMillis()}"
            
            // Make XHR request
            val xhrHeaders = buildXhrHeaders(embedUrl)
            val xhrRequest = Request.Builder()
                .url(xhrUrl)
                .headers(xhrHeaders)
                .build()
            
            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body?.string() ?: return emptyList()
            
            // Try to parse as JSON
            val json = JSONObject(xhrBody)
            
            // Try to get HLS URL first
            val hlsUrl = json.optString("hls", "").takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrEmpty()) {
                return playlistUtils.extractFromHls(
                    hlsUrl,
                    videoNameGen = { quality -> "Eporner - $quality" }
                )
            }
            
            // Try to get sources array with HLS
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    if (type.contains("hls") || src.contains(".m3u8")) {
                        return playlistUtils.extractFromHls(
                            src,
                            videoNameGen = { quality -> "Eporner - $quality" }
                        )
                    }
                }
            }
            
            // NO MP4 FALLBACK - return empty if no HLS
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        val matcher = videoIdPattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractHash(html: String): String? {
        val matcher = hashPattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun buildXhrHeaders(embedUrl: String): Headers {
        return headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/plain, */*")
            .set("Origin", "https://www.eporner.com")
            .build()
    }
}
