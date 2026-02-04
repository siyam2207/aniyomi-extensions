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
                "&supportedFormats=hls,mp4" +
                "&_=${System.currentTimeMillis()}"
            
            // Make XHR request
            val xhrHeaders = headers.newBuilder()
                .set("Referer", embedUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/plain, */*")
                .build()
            
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
            if (hlsUrl != null) {
                return playlistUtils.extractFromHls(
                    hlsUrl,
                    videoNameGen = { quality -> "Eporner - $quality" }
                )
            }
            
            // Try to get MP4 URL
            val mp4Url = json.optString("mp4", "").takeIf { it.isNotBlank() }
            if (mp4Url != null) {
                val quality = extractQualityFromUrl(mp4Url)
                return listOf(
                    Video(
                        url = mp4Url,
                        quality = quality,
                        videoUrl = mp4Url,
                        headers = buildVideoHeaders(embedUrl)
                    )
                )
            }
            
            // Try to parse sources array
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                val videos = mutableListOf<Video>()
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    if (type.contains("mp4") || src.endsWith(".mp4")) {
                        val quality = source.optString("label", "").takeIf { it.isNotBlank() }
                            ?: extractQualityFromUrl(src)
                        videos.add(
                            Video(
                                url = src,
                                quality = quality,
                                videoUrl = src,
                                headers = buildVideoHeaders(embedUrl)
                            )
                        )
                    }
                }
                if (videos.isNotEmpty()) return videos
            }
            
            // Fallback: extract MP4 URLs from raw response
            extractMp4Urls(xhrBody, embedUrl)
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

    private fun extractMp4Urls(body: String, embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        val pattern = Pattern.compile("\"(https?://[^\"]+\\.mp4[^\"]*)\"")
        val matcher = pattern.matcher(body)
        
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
            url.contains("/1080/") || url.contains("_1080.") -> "1080p"
            url.contains("/720/") || url.contains("_720.") -> "720p"
            url.contains("/480/") || url.contains("_480.") -> "480p"
            url.contains("/360/") || url.contains("_360.") -> "360p"
            url.contains("/240/") || url.contains("_240.") -> "240p"
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
