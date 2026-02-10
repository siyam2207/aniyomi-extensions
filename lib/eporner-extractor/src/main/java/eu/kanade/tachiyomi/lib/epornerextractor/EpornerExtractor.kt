package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

class EpornerExtractor(private val client: OkHttpClient, private val headers: Headers) {

private val playlistUtils by lazy { PlaylistUtils(client) }  

private val videoIdPattern = Pattern.compile("""/(?:embed|video)/(\d+)/?""")  
private val hashPattern = Pattern.compile("""window\.initialData\s*=\s*({.*?});""", Pattern.DOTALL)  
private val dataPattern = Pattern.compile("""data-video-id="(\d+)"\s+data-hash="([^"]+)""")  
private val scriptPattern = Pattern.compile("""<script[^>]*>(.*?)</script>""", Pattern.DOTALL)  

fun videosFromEmbed(url: String): List<Video> {  
    return try {  
        // Step 1: Extract video ID from URL  
        val videoId = extractVideoId(url) ?: return emptyList()  
          
        // Step 2: Fetch embed page  
        val embedRequest = Request.Builder()  
            .url(url)  
            .headers(headers)  
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")  
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")  
            .addHeader("Accept-Language", "en-US,en;q=0.5")  
            .addHeader("Accept-Encoding", "gzip, deflate, br")  
            .addHeader("DNT", "1")  
            .addHeader("Connection", "keep-alive")  
            .addHeader("Upgrade-Insecure-Requests", "1")  
            .addHeader("Sec-Fetch-Dest", "document")  
            .addHeader("Sec-Fetch-Mode", "navigate")  
            .addHeader("Sec-Fetch-Site", "none")  
            .addHeader("Sec-Fetch-User", "?1")  
            .build()  
          
        val embedResponse = client.newCall(embedRequest).execute()  
        val embedHtml = embedResponse.body?.string() ?: return emptyList()  
          
        // Step 3: Extract data from HTML using multiple methods  
        val hash = extractHashFromHTML(embedHtml)  
        val (extractedVideoId, extractedHash) = extractDataAttributes(embedHtml)  
          
        val finalHash = hash ?: extractedHash ?: return emptyList()  
        val finalVideoId = extractedVideoId ?: videoId  
          
        // Step 4: Build XHR URL with correct parameters  
        val timestamp = System.currentTimeMillis()  
        val xhrUrl = "https://www.eporner.com/api/v2/video/id/?id=$finalVideoId" +  
            "&hash=$finalHash" +  
            "&device=generic" +  
            "&device_id=generic" +  
            "&domain=www.eporner.com" +  
            "&embed=true" +  
            "&_=$timestamp"  
          
        // Step 5: Make XHR request  
        val xhrHeaders = Headers.Builder()  
            .add("Referer", url)  
            .add("X-Requested-With", "XMLHttpRequest")  
            .add("Accept", "application/json, text/plain, */*")  
            .add("Origin", "https://www.eporner.com")  
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")  
            .add("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")  
            .add("Accept-Encoding", "gzip, deflate, br")  
            .add("Connection", "keep-alive")  
            .build()  
          
        val xhrRequest = Request.Builder()  
            .url(xhrUrl)  
            .headers(xhrHeaders)  
            .build()  
          
        val xhrResponse = client.newCall(xhrRequest).execute()  
        val xhrBody = xhrResponse.body?.string() ?: return emptyList()  
          
        // Step 6: Parse JSON response  
        val json = JSONObject(xhrBody)  
          
        // Extract videos from JSON  
        extractVideosFromJson(json, url)  
          
    } catch (e: Exception) {  
        e.printStackTrace()  
        emptyList()  
    }  
}  

private fun extractVideosFromJson(json: JSONObject, refererUrl: String): List<Video> {  
    val videos = mutableListOf<Video>()  
      
    try {  
        // Try to get videos array  
        val videosArray = json.optJSONArray("videos")  
        if (videosArray != null) {  
            for (i in 0 until videosArray.length()) {  
                val videoObj = videosArray.getJSONObject(i)  
                val url = videoObj.optString("url", "")  
                val quality = videoObj.optString("quality", "")  
                if (url.isNotBlank() && quality.isNotBlank()) {  
                    if (url.contains(".m3u8")) {  
                        // Handle HLS stream  
                        val hlsVideos = extractHlsVideos(url, refererUrl)  
                        videos.addAll(hlsVideos)  
                    } else {  
                        // Direct MP4 URL  
                        val video = Video(url, "Eporner:$quality", url)  
                        videos.add(video)  
                    }  
                }  
            }  
        }  
          
        // Try to get sources object  
        val sources = json.optJSONObject("sources")  
        if (sources != null) {  
            val keys = sources.keys()  
            while (keys.hasNext()) {  
                val key = keys.next()  
                val sourceObj = sources.getJSONObject(key)  
                val url = sourceObj.optString("src", "")  
                val quality = sourceObj.optString("label", key)  
                  
                if (url.isNotBlank()) {  
                    if (url.contains(".m3u8")) {  
                        val hlsVideos = extractHlsVideos(url, refererUrl)  
                        videos.addAll(hlsVideos)  
                    } else {  
                        val video = Video(url, "Eporner:$quality", url)  
                        videos.add(video)  
                    }  
                }  
            }  
        }  
          
        // Try to get mp4 object  
        val mp4 = json.optJSONObject("mp4")  
        if (mp4 != null) {  
            val qualities = mp4.keys()  
            while (qualities.hasNext()) {  
                val quality = qualities.next()  
                val url = mp4.optString(quality, "")  
                if (url.isNotBlank()) {  
                    val video = Video(url, "Eporner:$quality", url)  
                    videos.add(video)  
                }  
            }  
        }  
          
        // Try to get hls object  
        val hls = json.optJSONObject("hls")  
        if (hls != null) {  
            val url = hls.optString("url", "")  
            if (url.isNotBlank()) {  
                val hlsVideos = extractHlsVideos(url, refererUrl)  
                videos.addAll(hlsVideos)  
            }  
        }  
          
        // Direct hls URL  
        val hlsUrl = json.optString("hls", "")  
        if (hlsUrl.isNotBlank()) {  
            val hlsVideos = extractHlsVideos(hlsUrl, refererUrl)  
            videos.addAll(hlsVideos)  
        }  
          
    } catch (e: Exception) {  
        e.printStackTrace()  
    }  
      
    return videos.distinctBy { it.url }  
}  

private fun extractHlsVideos(hlsUrl: String, refererUrl: String): List<Video> {  
    return try {  
        playlistUtils.extractFromHls(  
            hlsUrl,  
            referer = refererUrl,  
            videoNameGen = { quality -> "Eporner HLS:$quality" }  
        )  
    } catch (e: Exception) {  
        e.printStackTrace()  
        emptyList()  
    }  
}  

private fun extractVideoId(url: String): String? {  
    val matcher = videoIdPattern.matcher(url)  
    return if (matcher.find()) matcher.group(1) else null  
}  

private fun extractHashFromHTML(html: String): String? {  
    try {  
        // Method 1: Look for window.initialData  
        val matcher = hashPattern.matcher(html)  
        if (matcher.find()) {  
            val jsonStr = matcher.group(1)  
            val json = JSONObject(jsonStr)  
            return json.optJSONObject("video")?.optString("hash")  
        }  
          
        // Method 2: Look for hash in script tags  
        val scriptMatcher = scriptPattern.matcher(html)  
        while (scriptMatcher.find()) {  
            val scriptContent = scriptMatcher.group(1)  
            if (scriptContent.contains("hash")) {  
                val hashRegex = Pattern.compile("""['"]hash['"]\s*:\s*['"]([^'"]+)['"]""")  
                val hashMatcher = hashRegex.matcher(scriptContent)  
                if (hashMatcher.find()) {  
                    return hashMatcher.group(1)  
                }  
                  
                // Alternative pattern  
                val altPattern = Pattern.compile("""hash\s*=\s*['"]([^'"]+)['"]""")  
                val altMatcher = altPattern.matcher(scriptContent)  
                if (altMatcher.find()) {  
                    return altMatcher.group(1)  
                }  
            }  
        }  
    } catch (e: Exception) {  
        e.printStackTrace()  
    }  
    return null  
}  

private fun extractDataAttributes(html: String): Pair<String?, String?> {  
    val matcher = dataPattern.matcher(html)  
    return if (matcher.find()) {  
        Pair(matcher.group(1), matcher.group(2))  
    } else {  
        Pair(null, null)  
    }  
}

}
