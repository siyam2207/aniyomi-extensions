package eu.kanade.tachiyomi.lib.googledriveextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GoogleDriveExtractor(private val client: OkHttpClient, private val headers: Headers) {

    companion object {
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        // Public key yt-dlp uses for the same endpoint; there's nothing secret
        // about it, it's just how this API is called.
        private const val PLAYBACK_API_KEY = "AIzaSyDVQw45DwoYh632gvsP5vPDqEKvb-Ywnb8"
    }

    private val cookieList = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())

    // Full quality picker for a single uploaded file: Drive's own auto-
    // transcoded resolutions (unofficial, reverse-engineered API - failures
    // here are swallowed so a broken/rate-limited call doesn't kill playback)
    // plus the original uploaded file as a reliable fallback/best-quality
    // option. Use this for a plain episode with no separately-known quality.
    fun videosFromUrl(itemId: String, videoName: String = "Video"): List<Video> {
        val transcoded = runCatching { transcodedVideos(itemId, videoName) }.getOrDefault(emptyList())
        val original = originalVideo(itemId, videoName)
        return transcoded + original
    }

    // Just the original uploaded file, no transcoded ladder - for callers
    // that already know a specific quality for this file (e.g. from a
    // quality subfolder or filename tag) and want exactly one clean stream
    // labeled with that quality, not a further sub-list of Drive's own
    // auto-transcodes of the same file.
    fun originalVideoFromUrl(itemId: String, videoName: String = "Video"): Video = originalVideo(itemId, videoName)

    private fun originalVideo(itemId: String, videoName: String): Video {
        val url = "https://drive.usercontent.google.com/download?id=$itemId"
        val docHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Cookie", cookieList.toStr())
        }.build()

        val docResp = client.newCall(
            GET(url, docHeaders)
        ).execute()

        if (!docResp.peekBody(15).string().equals("<!DOCTYPE html>", true)) {
            return Video(url, videoName, url, docHeaders)
        }

        val document = docResp.asJsoup()

        val itemSize = document.selectFirst("span.uc-name-size")
            ?.let { " ${it.ownText().trim()} " }
            ?: ""

        val videoUrl = url.toHttpUrl().newBuilder().apply {
            document.select("input[type=hidden]").forEach {
                setQueryParameter(it.attr("name"), it.attr("value"))
            }
        }.build().toString()

        return Video(videoUrl, videoName + itemSize, videoUrl, docHeaders)
    }

    // Drive transcodes uploaded videos into several resolutions for its own
    // web preview player (same shared infrastructure as YouTube, same itag-
    // style scheme) - this pulls those out so a single upload can offer a
    // real quality picker instead of needing separate files per quality.
    // Unofficial API: Google reworked it once already (Oct 2025) and can
    // again without notice.
    private fun transcodedVideos(itemId: String, videoName: String): List<Video> {
        val playbackUrl = "https://content-workspacevideo-pa.googleapis.com/v1/drive/media/$itemId/playback"
            .toHttpUrl().newBuilder()
            .addQueryParameter("key", PLAYBACK_API_KEY)
            .build()

        val playbackHeaders = headers.newBuilder().apply {
            add("Accept", ACCEPT)
            add("Cookie", cookieList.toStr())
            add("Referer", "https://drive.google.com/")
        }.build()

        val responseBody = client.newCall(GET(playbackUrl.toString(), playbackHeaders)).execute().body
            ?.string() ?: return emptyList()

        val streaming = Json.parseToJsonElement(responseBody).jsonObject["mediaStreamingData"]
            ?.jsonObject?.get("formatStreamingData")?.jsonObject ?: return emptyList()

        val videos = mutableListOf<Video>()
        listOf("progressiveTranscodes", "adaptiveTranscodes").forEach { bucket ->
            streaming[bucket]?.jsonArray?.forEach { fmt ->
                val obj = fmt.jsonObject
                val streamUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val height = obj["transcodeMetadata"]?.jsonObject?.get("height")?.jsonPrimitive?.intOrNull
                val label = height?.let { "$videoName (${it}p)" } ?: "$videoName (Drive stream)"
                videos.add(Video(streamUrl, label, streamUrl, playbackHeaders))
            }
        }
        return videos
    }

    private fun List<Cookie>.toStr(): String {
        return this.joinToString("; ") { "${it.name}=${it.value}" }
    }
}
