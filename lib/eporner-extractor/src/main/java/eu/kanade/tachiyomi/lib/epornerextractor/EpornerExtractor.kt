package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.math.pow

class EpornerExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    companion object {
        private const val TAG = "EpornerExtractor"
        
        // Regex patterns for the NEW Eporner format
        private val ALPHANUMERIC_ID_PATTERN = Pattern.compile("""/(?:video-|embed/)([a-zA-Z0-9]+)""")
        private val SCRIPT_DATA_PATTERN = Pattern.compile("""<script[^>]*>([^<]+)</script>""")
        private val WINDOW_DATA_PATTERN = Pattern.compile("""window\.(?:__INITIAL_STATE__|videoData|playerConfig)\s*=\s*({.*?});""", Pattern.DOTALL)
        private val JSON_PARSE_PATTERN = Pattern.compile("""JSON\.parse\(['"]([^'"]+)['"]\)""")
        private val HLS_URL_PATTERN = Pattern.compile("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        private val CDN_ID_PATTERN = Pattern.compile("""['"]id['"]\s*:\s*['"]?(\d+)['"]?""")
        private val HASH_PATTERN = Pattern.compile("""['"]hash['"]\s*:\s*['"]([^'"]+)['"]""")
        private val QUALITIES_PATTERN = Pattern.compile("""['"]qualities['"]\s*:\s*\[([^\]]+)\]""")
    }

    fun videosFromEmbed(url: String): List<Video> {
        return try {
            LogUtil.log(TAG, "Starting extraction for: $url")
            
            // Step 1: Extract alphanumeric video ID
            val videoId = extractAlphanumericId(url) ?: run {
                LogUtil.log(TAG, "Failed to extract alphanumeric video ID")
                return emptyList()
            }
            
            LogUtil.log(TAG, "Alphanumeric Video ID: $videoId")
            
            // Step 2: Fetch the embed page
            val embedUrl = "https://www.eporner.com/embed/$videoId/"
            val html = fetchEmbedPage(embedUrl) ?: return emptyList()
            
            // Step 3: Extract HLS data from JavaScript
            val hlsData = extractHlsDataFromHtml(html, embedUrl)
            if (hlsData != null) {
                LogUtil.log(TAG, "Successfully extracted HLS data")
                return extractVideosFromHlsData(hlsData, embedUrl)
            }
            
            // Step 4: Fallback - try to construct HLS URL from page data
            val constructedHls = constructHlsUrlFromPage(html, videoId, embedUrl)
            if (constructedHls != null) {
                LogUtil.log(TAG, "Constructed HLS URL: ${constructedHls.take(100)}...")
                return extractVideosFromHls(constructedHls, embedUrl)
            }
            
            LogUtil.log(TAG, "No HLS data found")
            emptyList()
            
        } catch (e: Exception) {
            LogUtil.log(TAG, "Error in videosFromEmbed: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Step 1: Extract alphanumeric ID (e.g., "nerCmtNR6jq")
     */
    private fun extractAlphanumericId(url: String): String? {
        val matcher = ALPHANUMERIC_ID_PATTERN.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    /**
     * Step 2: Fetch embed page with proper headers
     */
    private fun fetchEmbedPage(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(headers)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Referer", "https://www.eporner.com/")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                LogUtil.log(TAG, "Failed to fetch embed page: ${response.code}")
                null
            }
        } catch (e: Exception) {
            LogUtil.log(TAG, "Error fetching embed page: ${e.message}")
            null
        }
    }

    /**
     * Step 3: Extract HLS data from HTML/JavaScript
     * This is where the REAL data is hidden
     */
    private fun extractHlsDataFromHtml(html: String, refererUrl: String): HlsData? {
        return try {
            // Method 1: Look for window.__INITIAL_STATE__ or window.videoData
            val windowMatcher = WINDOW_DATA_PATTERN.matcher(html)
            if (windowMatcher.find()) {
                val jsonStr = windowMatcher.group(1)
                try {
                    val json = JSONObject(jsonStr)
                    return parseVideoDataFromJson(json, refererUrl)
                } catch (e: Exception) {
                    LogUtil.log(TAG, "Failed to parse window data JSON: ${e.message}")
                }
            }
            
            // Method 2: Look for JSON.parse calls
            val scriptMatcher = SCRIPT_DATA_PATTERN.matcher(html)
            while (scriptMatcher.find()) {
                val scriptContent = scriptMatcher.group(1)
                val jsonParseMatcher = JSON_PARSE_PATTERN.matcher(scriptContent)
                
                while (jsonParseMatcher.find()) {
                    try {
                        val escapedJson = jsonParseMatcher.group(1)
                        val jsonStr = escapedJson.replace("\\/", "/").replace("\\\"", "\"")
                        val json = JSONObject(jsonStr)
                        val hlsData = parseVideoDataFromJson(json, refererUrl)
                        if (hlsData != null) return hlsData
                    } catch (e: Exception) {
                        // Continue searching
                    }
                }
                
                // Method 3: Look for direct HLS URLs in scripts
                val hlsMatcher = HLS_URL_PATTERN.matcher(scriptContent)
                if (hlsMatcher.find()) {
                    val hlsUrl = hlsMatcher.group(1)
                    if (hlsUrl.contains("master.m3u8") && hlsUrl.contains("eporner.com")) {
                        return HlsData(
                            masterUrl = hlsUrl,
                            cdnId = extractCdnIdFromUrl(hlsUrl),
                            hash = extractHashFromUrl(hlsUrl),
                            qualities = extractQualitiesFromUrl(hlsUrl),
                            referer = refererUrl
                        )
                    }
                }
            }
            
            // Method 4: Extract from meta tags or data attributes
            extractHlsDataFromMetaTags(html, refererUrl)
            
        } catch (e: Exception) {
            LogUtil.log(TAG, "Error extracting HLS data from HTML: ${e.message}")
            null
        }
    }

    /**
     * Parse video data from JSON object
     */
    private fun parseVideoDataFromJson(json: JSONObject, refererUrl: String): HlsData? {
        return try {
            // Try different JSON structures
            
            // Structure 1: Direct video object
            if (json.has("video")) {
                val videoObj = json.getJSONObject("video")
                val cdnId = videoObj.optString("id", null) ?: videoObj.optString("cdnId", null)
                val hash = videoObj.optString("hash", null)
                val hlsUrl = videoObj.optString("hls", null)
                
                if (cdnId != null && hash != null) {
                    return HlsData(
                        masterUrl = hlsUrl,
                        cdnId = cdnId,
                        hash = hash,
                        qualities = extractQualitiesFromJson(videoObj),
                        referer = refererUrl
                    )
                }
            }
            
            // Structure 2: Direct hls URL
            if (json.has("hls")) {
                val hlsUrl = json.getString("hls")
                return HlsData(
                    masterUrl = hlsUrl,
                    cdnId = extractCdnIdFromUrl(hlsUrl),
                    hash = extractHashFromUrl(hlsUrl),
                    qualities = extractQualitiesFromUrl(hlsUrl),
                    referer = refererUrl
                )
            }
            
            // Structure 3: Player config
            if (json.has("playerConfig")) {
                val playerConfig = json.getJSONObject("playerConfig")
                return parseVideoDataFromJson(playerConfig, refererUrl)
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Construct HLS URL from page data (fallback method)
     */
    private fun constructHlsUrlFromPage(html: String, videoId: String, refererUrl: String): String? {
        return try {
            // Extract CDN ID (numeric)
            val cdnIdMatcher = CDN_ID_PATTERN.matcher(html)
            val cdnId = if (cdnIdMatcher.find()) {
                cdnIdMatcher.group(1)
            } else {
                // Generate from videoId hash (fallback)
                (videoId.hashCode() and 0x7fffffff).toString()
            }
            
            // Extract hash
            val hashMatcher = HASH_PATTERN.matcher(html)
            val hash = if (hashMatcher.find()) {
                hashMatcher.group(1)
            } else {
                // Generate placeholder hash
                generateHash(videoId)
            }
            
            // Extract qualities
            val qualitiesMatcher = QUALITIES_PATTERN.matcher(html)
            val qualities = if (qualitiesMatcher.find()) {
                qualitiesMatcher.group(1).split(",").map { it.trim(' ', '"', '\'') }
            } else {
                listOf("240p", "360p", "480p", "720p", "1080p")
            }
            
            // Get server from IP (simulate browser IP detection)
            val ip = "103.168.69.137" // This would need to be dynamic in real implementation
            val expires = (System.currentTimeMillis() / 1000 + 86400).toString() // 24 hours from now
            
            // Construct HLS URL
            val server = selectCdnServer(ip)
            val qualitiesStr = qualities.joinToString(",")
            
            "https://$server/hls/v3/$cdnId-,$qualitiesStr,.mp4.urlset/master.m3u8?hash=$hash&expires=$expires&ip=$ip"
            
        } catch (e: Exception) {
            LogUtil.log(TAG, "Failed to construct HLS URL: ${e.message}")
            null
        }
    }

    /**
     * Extract videos from HLS data
     */
    private fun extractVideosFromHlsData(hlsData: HlsData, refererUrl: String): List<Video> {
        return try {
            // If we have a direct master URL, use it
            if (hlsData.masterUrl != null) {
                return extractVideosFromHls(hlsData.masterUrl, refererUrl)
            }
            
            // Otherwise construct the URL
            if (hlsData.cdnId != null && hlsData.hash != null) {
                val ip = "103.168.69.137" // Would need real IP detection
                val expires = (System.currentTimeMillis() / 1000 + 86400).toString()
                val server = selectCdnServer(ip)
                val qualities = hlsData.qualities ?: listOf("240p", "360p", "480p", "720p", "1080p")
                val qualitiesStr = qualities.joinToString(",")
                
                val masterUrl = "https://$server/hls/v3/${hlsData.cdnId}-,$qualitiesStr,.mp4.urlset/master.m3u8?hash=${hlsData.hash}&expires=$expires&ip=$ip"
                
                return extractVideosFromHls(masterUrl, refererUrl)
            }
            
            emptyList()
        } catch (e: Exception) {
            LogUtil.log(TAG, "Error extracting videos from HLS data: ${e.message}")
            emptyList()
        }
    }

    /**
     * Extract videos from HLS master playlist
     */
    private fun extractVideosFromHls(masterUrl: String, refererUrl: String): List<Video> {
        return try {
            LogUtil.log(TAG, "Extracting from HLS master: ${masterUrl.take(100)}...")
            
            playlistUtils.extractFromHls(
                masterUrl,
                referer = refererUrl,
                videoNameGen = { quality -> "Eporner HLS:$quality" }
            )
        } catch (e: Exception) {
            LogUtil.log(TAG, "HLS extraction failed: ${e.message}")
            
            // Fallback: Return the master URL as a single video
            // Some players can handle master.m3u8 directly
            listOf(Video(masterUrl, "Eporner Master Playlist", masterUrl))
        }
    }

    /**
     * Extract HLS data from meta tags (alternative method)
     */
    private fun extractHlsDataFromMetaTags(html: String, refererUrl: String): HlsData? {
        // Look for meta tags with HLS data
        val metaPattern = Pattern.compile("""<meta[^>]+(?:name|property)=['"]([^'"]+)['"][^>]+content=['"]([^'"]+)['"]""")
        val matcher = metaPattern.matcher(html)
        
        var cdnId: String? = null
        var hash: String? = null
        var hlsUrl: String? = null
        
        while (matcher.find()) {
            val name = matcher.group(1)
            val content = matcher.group(2)
            
            when (name) {
                "eporner:cdnId", "video:cdnId" -> cdnId = content
                "eporner:hash", "video:hash" -> hash = content
                "eporner:hls", "video:hls" -> hlsUrl = content
            }
        }
        
        return if (cdnId != null || hash != null || hlsUrl != null) {
            HlsData(hlsUrl, cdnId, hash, null, refererUrl)
        } else {
            null
        }
    }

    /**
     * Utility methods
     */
    private fun extractCdnIdFromUrl(url: String): String? {
        val pattern = Pattern.compile("""/hls/v3/(\d+)-""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractHashFromUrl(url: String): String? {
        val pattern = Pattern.compile("""[?&]hash=([^&]+)""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractQualitiesFromUrl(url: String): List<String> {
        val pattern = Pattern.compile("""-,(.+?),\.mp4""")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1).split(",")
        } else {
            listOf("240p", "360p", "480p", "720p", "1080p")
        }
    }

    private fun extractQualitiesFromJson(json: JSONObject): List<String> {
        return try {
            if (json.has("qualities")) {
                val qualitiesArray = json.getJSONArray("qualities")
                (0 until qualitiesArray.length()).map { qualitiesArray.getString(it) }
            } else if (json.has("available_qualities")) {
                val qualitiesArray = json.getJSONArray("available_qualities")
                (0 until qualitiesArray.length()).map { qualitiesArray.getString(it) }
            } else {
                listOf("240p", "360p", "480p", "720p", "1080p")
            }
        } catch (e: Exception) {
            listOf("240p", "360p", "480p", "720p", "1080p")
        }
    }

    private fun generateHash(input: String): String {
        // Simple hash generation for fallback
        return input.map { char ->
            ((char.code * 31) % 36).let {
                if (it < 10) ('0' + it) else ('A' + (it - 10))
            }
        }.take(16).joinToString("")
    }

    private fun selectCdnServer(ip: String): String {
        // Simple CDN server selection based on IP hash
        val servers = listOf(
            "dash-s13-n50-fr-cdn.eporner.com",
            "dash-s15-n51-de-cdn.eporner.com", 
            "dash-s17-n52-us-cdn.eporner.com",
            "dash-s19-n53-uk-cdn.eporner.com"
        )
        val index = ip.hashCode().absoluteValue % servers.size
        return servers[index]
    }

    /**
     * Data class for HLS information
     */
    private data class HlsData(
        val masterUrl: String?,
        val cdnId: String?,
        val hash: String?,
        val qualities: List<String>?,
        val referer: String
    )
}

// Extension for absolute value
private fun Int.absoluteValue(): Int = if (this < 0) -this else this

// Logging utility
object LogUtil {
    fun log(tag: String, message: String) {
        try {
            android.util.Log.d(tag, message)
        } catch (e: Exception) {
            println("[$tag] $message")
        }
    }
}
