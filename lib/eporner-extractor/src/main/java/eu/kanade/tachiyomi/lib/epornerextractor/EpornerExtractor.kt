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
    private val hashPattern = Pattern.compile("""hash\s*[:=]\s*['"]([a-zA-Z0-9]{20,})['"]""")
    
    // Mobile User-Agent to avoid bot detection (exactly as specified)
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

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
            
            // Step 4: Build XHR URL - request both HLS and MP4 formats
            val xhrUrl = "https://www.eporner.com/xhr/video/$videoId" +
                "?hash=$hash" +
                "&domain=www.eporner.com" +
                "&embed=true" +
                "&supportedFormats=hls,mp4" +
                "&_=${System.currentTimeMillis()}"
            
            // Step 5: Make XHR request with correct headers
            val xhrHeaders = Headers.Builder()
                .set("Referer", embedUrl)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/plain, */*")
                .set("Origin", "https://www.eporner.com")
                .set("User-Agent", mobileUserAgent)
                .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .build()
            
            val xhrRequest = Request.Builder()
                .url(xhrUrl)
                .headers(xhrHeaders)
                .build()
            
            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body?.string() ?: return emptyList()
            
            // Step 6: Parse JSON response
            val json = JSONObject(xhrBody)
            
            // Step 7: Try to get HLS URL first (preferred format)
            val hlsUrl = json.optString("hls", "").takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrEmpty()) {
                return extractAllQualitiesFromMasterPlaylist(hlsUrl)
            }
            
            // Step 8: Try to get sources array (check for HLS first)
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    if (type.contains("hls") || src.contains(".m3u8")) {
                        return extractAllQualitiesFromMasterPlaylist(src)
                    }
                }
            }
            
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractAllQualitiesFromMasterPlaylist(masterPlaylistUrl: String): List<Video> {
        return try {
            // Download and parse the master playlist
            val masterRequest = Request.Builder()
                .url(masterPlaylistUrl)
                .headers(
                    Headers.Builder()
                        .set("Referer", "https://www.eporner.com/")
                        .set("Origin", "https://www.eporner.com")
                        .set("User-Agent", mobileUserAgent)
                        .set("Accept", "*/*")
                        .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                        .set("Cache-Control", "no-cache")
                        .set("Pragma", "no-cache")
                        .build()
                )
                .build()
            
            val masterResponse = client.newCall(masterRequest).execute()
            val masterContent = masterResponse.body?.string() ?: return emptyList()
            
            // Parse the master playlist to find quality variants
            val lines = masterContent.lines()
            val videos = mutableListOf<Video>()
            
            var currentQuality: String? = null
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                // Look for quality information
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract quality from the line
                    currentQuality = when {
                        line.contains("720p") || line.contains("1280x720") -> "720p"
                        line.contains("480p") || line.contains("854x480") -> "480p"
                        line.contains("360p") || line.contains("640x360") -> "360p"
                        line.contains("240p") || line.contains("426x240") -> "240p"
                        else -> "Auto"
                    }
                    
                    // Next line should be the variant playlist URL
                    if (i + 1 < lines.size) {
                        val variantUrl = lines[i + 1].trim()
                        if (variantUrl.isNotBlank() && !variantUrl.startsWith("#")) {
                            // Make URL absolute if relative
                            val absoluteUrl = if (variantUrl.startsWith("http")) {
                                variantUrl
                            } else {
                                val baseUrl = masterPlaylistUrl.substringBeforeLast("/") + "/"
                                baseUrl + variantUrl
                            }
                            
                            videos.add(
                                Video(
                                    absoluteUrl,
                                    currentQuality,
                                    absoluteUrl,
                                    Headers.Builder()
                                        .set("Referer", "https://www.eporner.com/")
                                        .set("Origin", "https://www.eporner.com")
                                        .set("User-Agent", mobileUserAgent)
                                        .set("Accept", "*/*")
                                        .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                                        .set("Cache-Control", "no-cache")
                                        .set("Pragma", "no-cache")
                                        .build()
                                )
                            )
                            
                            currentQuality = null
                        }
                    }
                }
            }
            
            // If no variants found, return the master playlist itself
            if (videos.isEmpty()) {
                videos.add(
                    Video(
                        masterPlaylistUrl,
                        "HLS",
                        masterPlaylistUrl,
                        Headers.Builder()
                            .set("Referer", "https://www.eporner.com/")
                            .set("Origin", "https://www.eporner.com")
                            .set("User-Agent", mobileUserAgent)
                            .set("Accept", "*/*")
                            .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                            .set("Cache-Control", "no-cache")
                            .set("Pragma", "no-cache")
                            .build()
                    )
                )
            }
            
            videos
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
