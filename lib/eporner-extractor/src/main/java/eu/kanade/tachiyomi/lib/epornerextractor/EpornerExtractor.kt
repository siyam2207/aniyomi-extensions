package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

class EpornerExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val videoIdPattern = Pattern.compile("/embed/([^/]+)/?")
    private val hashPattern = Pattern.compile("""hash\s*[:=]\s*['"]([a-zA-Z0-9]{20,})['"]""")

    fun videosFromEmbed(url: String): List<Video> {
        return try {
            // Step 1: Extract video ID from URL
            val videoId = extractVideoId(url) ?: return emptyList()
            
            // Step 2: Fetch embed page to get hash
            val document = client.newCall(GET(url, headers)).execute().asJsoup()
            val embedHtml = document.html()
            
            // Step 3: Extract hash from HTML
            val hash = extractHash(embedHtml) ?: return emptyList()
            
            // Step 4: Build XHR URL
            val xhrUrl = "https://www.eporner.com/xhr/video/$videoId" +
                "?hash=$hash" +
                "&domain=www.eporner.com" +
                "&embed=true" +
                "&supportedFormats=hls,mp4" +
                "&_=${System.currentTimeMillis()}"
            
            // Step 5: Make XHR request with correct headers
            val xhrHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/plain, */*")
                .set("Origin", "https://www.eporner.com")
                .set("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")
                .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
                .set("Cache-Control", "no-cache")
                .set("Pragma", "no-cache")
                .build()
            
            val response = client.newCall(GET(xhrUrl, xhrHeaders)).execute()
            val xhrBody = response.body?.string() ?: return emptyList()
            
            // Step 6: Parse JSON response
            val json = JSONObject(xhrBody)
            
            // Step 7: Try to get HLS URL first (preferred format)
            val hlsUrl = json.optString("hls", "").takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrEmpty()) {
                return extractHlsVideos(hlsUrl)
            }
            
            // Step 8: Try to get sources array
            val sources = json.optJSONArray("sources")
            if (sources != null) {
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val src = source.optString("src", "").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type", "")
                    if (type.contains("hls") || src.contains(".m3u8")) {
                        return extractHlsVideos(src)
                    }
                }
            }
            
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun extractHlsVideos(hlsUrl: String): List<Video> {
        return try {
            playlistUtils.extractFromHls(
                hlsUrl,
                referer = "https://www.eporner.com/",
                videoNameGen = { quality -> "Eporner:$quality" }
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

    private fun extractHash(html: String): String? {
        val matcher = hashPattern.matcher(html)
        return if (matcher.find()) {
            val hash = matcher.group(1)
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
