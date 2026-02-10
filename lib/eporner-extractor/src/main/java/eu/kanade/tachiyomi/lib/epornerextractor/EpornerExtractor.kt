package eu.kanade.tachiyomi.animeextension.all.eporner.extractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.util.asJsoup

class EpornerExtractor(
    private val client: OkHttpClient
) {
    fun videosFromUrl(url: String): List<Video> {
        val videoList = mutableListOf<Video>()

        // ===== HEADERS (ANTI-403) =====
        val headers = Headers.Builder()
            .add("Referer", "https://www.eporner.com/")
            .add("Origin", "https://www.eporner.com")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            )
            .build()

        // ===== REQUEST MASTER PLAYLIST =====
        val request = GET(url, headers)
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) return emptyList()

        val body = response.body.string()

        // ===== PARSE QUALITY STREAMS =====
        val lines = body.split("\n")

        var quality = "Auto"

        lines.forEach { line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {

                val resMatch = Regex("""RESOLUTION=\d+x(\d+)""")
                    .find(line)

                quality = resMatch?.groupValues?.get(1)?.plus("p")
                    ?: "Auto"
            }

            if (line.startsWith("http")) {
                videoList.add(
                    Video(
                        url = line,
                        quality = "Eporner • $quality",
                        videoUrl = line,
                        headers = headers
                    )
                )
            }
        }

        return videoList
    }
}
