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
                .build()
            
            val xhrRequest = Request.Builder()
                .url(xhrUrl)
                .headers(xhrHeaders)
                .build()
            
            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body?.string() ?: return emptyList()
            
            // Step 6: Parse JSON response
            val json = JSONObject(xhrBody)
            
            // Step 7: Get HLS URL
            val hlsUrl = json.optString("hls", "").takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrEmpty()) {
                return extractHlsVariants(hlsUrl)
            }
            
            // Step 8: Try to get HLS URL from sources array
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    
                    if (type.contains("hls") || src.contains(".m3u8")) {
                        return extractHlsVariants(src)
                    }
                }
            }
            
            // If no HLS sources found, return empty list
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractHlsVariants(masterPlaylistUrl: String): List<Video> {
        return try {
            // Fetch the master playlist
            val playlistRequest = Request.Builder()
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
            
            val playlistResponse = client.newCall(playlistRequest).execute()
            val playlistContent = playlistResponse.body?.string() ?: return emptyList()
            
            // Parse the master playlist to extract variants
            val variants = mutableListOf<Video>()
            val lines = playlistContent.lines()
            
            var currentQuality: String? = null
            var currentUrl: String? = null
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                // Look for stream information lines (contains BANDWIDTH and RESOLUTION)
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract quality from resolution if available
                    val resolutionMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(line)
                    val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                    
                    currentQuality = when {
                        resolutionMatch != null -> {
                            val res = resolutionMatch.groupValues[1]
                            when {
                                res.contains("1280x720") -> "720p"
                                res.contains("854x480") -> "480p"
                                res.contains("640x360") -> "360p"
                                res.contains("426x240") -> "240p"
                                else -> res
                            }
                        }
                        bandwidthMatch != null -> {
                            val bandwidth = bandwidthMatch.groupValues[1].toInt()
                            when {
                                bandwidth > 2000000 -> "720p"
                                bandwidth > 1000000 -> "480p"
                                bandwidth > 500000 -> "360p"
                                else -> "240p"
                            }
                        }
                        else -> "Auto"
                    }
                    
                    // Next line should be the variant playlist URL
                    if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1].trim()
                        if (!nextLine.startsWith("#")) {
                            currentUrl = nextLine
                            
                            // Make relative URLs absolute
                            currentUrl = if (currentUrl!!.startsWith("http")) {
                                currentUrl
                            } else {
                                // Extract base URL from master playlist
                                val baseUrl = masterPlaylistUrl.substringBeforeLast("/") + "/"
                                baseUrl + currentUrl
                            }
                            
                            // Add the variant to the list
                            variants.add(
                                Video(
                                    currentUrl,
                                    currentQuality,
                                    currentUrl,
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
                            
                            // Reset for next variant
                            currentQuality = null
                            currentUrl = null
                        }
                    }
                }
            }
            
            // If no variants were found (direct playlist), add the master playlist itself
            if (variants.isEmpty()) {
                variants.add(
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
            
            variants
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
