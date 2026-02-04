package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

class EpornerExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val videoIdPattern = Pattern.compile("/embed/([^/]+)/?")
    private val hashPattern = Pattern.compile("""hash\s*[:=]\s*['"]([^'"]+)['"]""")
    
    // Mobile User-Agent to avoid bot detection
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; SM-M526BR) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    fun videosFromEmbed(embedUrl: String): List<Video> {
        return try {
            // Step 1: Extract video ID from URL
            val videoId = extractVideoId(embedUrl) ?: return emptyList()
            
            // Step 2: Fetch embed page to get hash
            val embedRequest = Request.Builder()
                .url(embedUrl)
                .headers(headers)
                .build()
            
            val embedResponse = client.newCall(embedRequest).execute()
            val embedHtml = embedResponse.body?.string() ?: return emptyList()
            
            // Step 3: Extract hash from HTML
            val hash = extractHash(embedHtml) ?: return emptyList()
            
            // Step 4: Build XHR URL - ONLY request HLS format
            val xhrUrl = "https://www.eporner.com/xhr/video/$videoId" +
                "?hash=$hash" +
                "&domain=www.eporner.com" +
                "&embed=true" +
                "&supportedFormats=hls" +  // ONLY HLS, no MP4
                "&_=${System.currentTimeMillis()}"
            
            // Step 5: Make XHR request with correct headers
            val xhrHeaders = Headers.Builder()
                .set("Referer", embedUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/plain, */*")
                .set("Origin", "https://www.eporner.com")
                .set("User-Agent", mobileUserAgent)
                .build()
            
            val xhrRequest = Request.Builder()
                .url(xhrUrl)
                .headers(xhrHeaders)
                .build()
            
            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body?.string() ?: return emptyList()
            
            // Step 6: Parse JSON response
            val json = JSONObject(xhrBody)
            
            // Step 7: Try to get HLS URL from direct "hls" field
            val hlsUrl = json.optString("hls", "").takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrEmpty()) {
                return listOf(
                    Video(
                        hlsUrl,        // displayUrl - shown in UI
                        "HLS",         // quality - shown in UI
                        hlsUrl,        // videoUrl - actual video URL for player
                        Headers.Builder()
                            .set("Referer", "https://www.eporner.com/")
                            .set("Origin", "https://www.eporner.com")
                            .set("User-Agent", mobileUserAgent)
                            .build()
                    )
                )
            }
            
            // Step 8: Try to get HLS URL from sources array
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    
                    // Only accept HLS/m3u8 formats
                    if (type.contains("hls") || src.contains(".m3u8")) {
                        return listOf(
                            Video(
                                src,        // displayUrl
                                "HLS",      // quality
                                src,        // videoUrl
                                Headers.Builder()
                                    .set("Referer", "https://www.eporner.com/")
                                    .set("Origin", "https://www.eporner.com")
                                    .set("User-Agent", mobileUserAgent)
                                    .build()
                            )
                        )
                    }
                }
            }
            
            // If no HLS sources found, return empty list (NO MP4 fallback)
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
        return if (matcher.find()) {
            val hash = matcher.group(1)
            // Additional validation: hash should be 20-32 alphanumeric chars
            if (hash.matches(Regex("[a-zA-Z0-9]{20,32}"))) {
                hash
            } else {
                null
            }
        } else {
            null
        }
    }
}
