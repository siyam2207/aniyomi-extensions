package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

internal class EpornerVideoExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {

    private val tag = "EpornerExtractor"

    fun extract(response: Response): List<Video> {
        return try {
            val document = response.asJsoup()
            val videos = mutableListOf<Video>()

            // JS pattern
            document.select("script").forEach { script ->
                val pattern = Regex(
                    """quality["']?\s*:\s*["']?(\d+)["']?\s*,\s*videoUrl["']?\s*:\s*["']([^"']+)["']"""
                )
                pattern.findAll(script.html()).forEach { match ->
                    val quality = match.groupValues[1]
                    val videoUrl = match.groupValues[2]
                    if (videoUrl.isNotBlank()) {
                        videos.add(Video(videoUrl, "Eporner - ${quality}p", videoUrl))
                    }
                }
            }

            // HLS streams
            if (videos.isEmpty()) {
                document.select("script").forEach { script ->
                    val hlsPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                    hlsPattern.findAll(script.html()).forEach { match ->
                        val url = match.value
                        if (url.contains(".m3u8")) {
                            try {
                                val playlistUtils = PlaylistUtils(client, headers)
                                videos.addAll(
                                    playlistUtils.extractFromHls(
                                        url,
                                        response.request.url.toString(),
                                        videoNameGen = { q -> "HLS - $q" },
                                    ),
                                )
                            } catch (e: Exception) {
                                Log.e(tag, "HLS extraction failed: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Direct MP4 fallback
            if (videos.isEmpty()) {
                val mp4Patterns = listOf(
                    Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']"""),
                    Regex("""(https?://[^"'\s]+\.mp4)"""),
                )
                val html = document.html()
                mp4Patterns.forEach { pattern ->
                    pattern.findAll(html).forEach { match ->
                        val url = match.groupValues.getOrNull(1) ?: match.value
                        if (url.isNotBlank() && url.startsWith("http") && videos.none { it.videoUrl == url }) {
                            addVideoWithQuality(videos, url)
                        }
                    }
                }
            }

            videos.distinctBy { it.videoUrl }.sortedByPreference()
        } catch (e: Exception) {
            Log.e(tag, "Video extraction error: ${e.message}", e)
            emptyList()
        }
    }

    private fun addVideoWithQuality(videos: MutableList<Video>, url: String) {
        val quality = when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Unknown"
        }
        videos.add(Video(url, "Direct - $quality", url))
    }

    private fun List<Video>.sortedByPreference(): List<Video> {
        val qualityPref = preferences.getString(EpornerPreferences.PREF_QUALITY_KEY, "720p")!!
        return sortedWith(
            compareByDescending {
                when {
                    qualityPref == "best" -> it.quality.replace("p", "").toIntOrNull() ?: 0
                    it.quality.contains(qualityPref) -> 1000
                    else -> 0
                }
            },
        )
    }
}
