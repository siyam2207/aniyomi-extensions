package eu.kanade.tachiyomi.lib.epornerextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

class EpornerExtractor(
private val client: OkHttpClient,
private val headers: Headers,
) {

```
private val playlistUtils by lazy { PlaylistUtils(client) }

// Regex to capture signed master.m3u8 URLs
private val hlsPattern = Pattern.compile(
    """https://[^"]+\.m3u8[^"]*""",
    Pattern.CASE_INSENSITIVE,
)

// Regex to capture direct MP4 links (fallback)
private val mp4Pattern = Pattern.compile(
    """https://[^"]+\.mp4[^"]*""",
    Pattern.CASE_INSENSITIVE,
)

fun videosFromEmbed(url: String): List<Video> {
    return try {
        // 1️⃣ Load embed page
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .addHeader("Referer", "https://www.eporner.com/")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .build()

        val html = client.newCall(request)
            .execute()
            .body
            ?.string()
            ?: return emptyList()

        val videos = mutableListOf<Video>()

        // 2️⃣ Extract HLS master playlist
        val hlsMatcher = hlsPattern.matcher(html)

        while (hlsMatcher.find()) {
            val masterUrl = hlsMatcher.group()

            val hlsVideos = playlistUtils.extractFromHls(
                masterUrl,
                referer = "https://www.eporner.com/",
                videoNameGen = { quality ->
                    "Eporner HLS:$quality"
                },
            )

            videos.addAll(hlsVideos)
        }

        // 3️⃣ Fallback: Direct MP4 links
        val mp4Matcher = mp4Pattern.matcher(html)

        while (mp4Matcher.find()) {
            val mp4Url = mp4Matcher.group()

            videos.add(
                Video(
                    mp4Url,
                    "Eporner MP4",
                    mp4Url,
                    headers = headers,
                ),
            )
        }

        videos.distinctBy { it.url }

    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}
```

}
