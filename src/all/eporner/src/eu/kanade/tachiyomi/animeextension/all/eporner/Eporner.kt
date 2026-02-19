package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val apiBaseUrl = "https://www.eporner.com/api/v2/video"
    private val json: Json by injectLazy()

    // ====== API Data Classes (for search/list endpoints only) ======
    @Serializable
    data class ApiSearchResponse(
        val videos: List<ApiVideo>?,
        val total_count: Int,
        val page: Int,
        val per_page: Int,
        val total_pages: Int,
    )

    @Serializable
    data class ApiVideo(
        val id: String,
        val title: String,
        val description: String? = null,
        val keywords: String? = null,
        val views: Int,
        val rate: String,
        val url: String,
        val added: String,
        val length_sec: Int,
        val default_thumb: ThumbDetails? = null,
    )

    @Serializable
    data class ThumbDetails(
        val src: String,
    )

    // ====== Popular ======
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "most-popular")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val apiResponse = parseSearchResponse(response)
        val animeList = apiResponse.videos?.mapNotNull { it.toSAnime() } ?: emptyList()
        val hasNextPage = apiResponse.page < apiResponse.total_pages
        return AnimesPage(animeList, hasNextPage)
    }

    // ====== Latest ======
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // ====== Search ======
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$apiBaseUrl/search/".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("query", query.ifEmpty { "all" })
            .addQueryParameter("order", "latest")
            .addQueryParameter("per_page", "40")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ====== Anime Details ======
    override fun animeDetailsRequest(anime: SAnime): Request {
        // anime.url is the video page URL from the list
        return GET(anime.url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
        return SAnime.create().apply {
            url = anime.url  // keep the original video page URL
            title = extractTitle(document) ?: ""
            thumbnail_url = extractThumbnail(document)
            description = buildDescription(document)
            artist = extractActors(document)?.joinToString(", ")
            status = SAnime.COMPLETED
        }
    }

    // ====== Episode List ======
    override fun episodeListParse(response: Response): List<SEpisode> {
        // response is the same as in animeDetailsParse, but we can re‑extract the video ID from the stored URL
        val videoId = anime.url.substringAfter("/video-").substringBefore("/")
        val embedUrl = buildEmbedUrl(videoId)
        val episode = SEpisode.create().apply {
            name = "Video"
            episode_number = 1f
            url = embedUrl
            date_upload = 0L  // upload date not needed for video extraction
        }
        return listOf(episode)
    }

    // ====== Video List ======
    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        val videos = mutableListOf<Video>()

        val scripts = document.select("script").eachText()
        for (script in scripts) {
            if (script.contains("sources") && script.contains("src")) {
                val jsonCandidate = extractJsonObject(script)
                if (jsonCandidate != null) {
                    val extracted = extractVideoFromJson(jsonCandidate)
                    if (extracted.isNotEmpty()) {
                        videos.addAll(extracted)
                        break
                    }
                }
            }
        }

        if (videos.isEmpty()) {
            val videoElements = document.select("video source[src], source[src*=.mp4], source[src*=.m3u8]")
            videoElements.forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val quality = element.attr("label").ifEmpty { "Unknown" }
                    videos.add(Video(src, quality, src, headers = headers))
                }
            }
        }

        return videos.sortedWith(
            compareByDescending<Video> { it.quality.extractQualityNumber() }
                .thenBy { it.quality },
        )
    }

    // ====== Helper Functions ======
    private fun parseSearchResponse(response: Response): ApiSearchResponse {
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("application/json")) {
            return ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
        val responseBody = response.body.string()
        if (responseBody.isBlank()) {
            return ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
        return try {
            json.decodeFromString<ApiSearchResponse>(responseBody)
        } catch (e: Exception) {
            ApiSearchResponse(emptyList(), 0, 1, 40, 1)
        }
    }

    private fun ApiVideo.toSAnime(): SAnime {
        return SAnime.create().apply {
            title = this@toSAnime.title
            url = normalizeUrl(this@toSAnime.url)
            thumbnail_url = this@toSAnime.default_thumb?.src
            description = this@toSAnime.keywords ?: this@toSAnime.description
            artist = this@toSAnime.keywords
            status = SAnime.COMPLETED
        }
    }

    // ----- Extraction from video page -----
    private fun extractTitle(document: Document): String? {
        return document.selectFirst("h1")?.text()
    }

    private fun extractThumbnail(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
    }

    private fun extractActors(document: Document): List<String>? {
        // Try JSON-LD first
        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        if (jsonLd != null) {
            try {
                val obj = json.parseToJsonElement(jsonLd).jsonObject
                val actorArray = obj["actor"]?.jsonArray
                if (!actorArray.isNullOrEmpty()) {
                    return actorArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        // Fallback to HTML tags
        val actorElements = document.select("li.vit-pornstar a")
        if (actorElements.isNotEmpty()) {
            return actorElements.mapNotNull { it.text().ifBlank { null } }
        }
        return null
    }

    private fun extractUploader(document: Document): String? {
        return document.selectFirst("li.vit-uploader a")?.text()
    }

    private fun extractTags(document: Document): List<String> {
        return document.select("li.vit-category a").mapNotNull { it.text().ifBlank { null } }
    }

    private fun extractViews(document: Document): String? {
        val viewsElem = document.selectFirst("#cinemaviews1")
        if (viewsElem != null) return viewsElem.text()
        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        if (jsonLd != null) {
            try {
                val obj = json.parseToJsonElement(jsonLd).jsonObject
                val interaction = obj["interactionStatistic"]?.jsonObject
                val count = interaction?.get("userInteractionCount")?.jsonPrimitive?.content
                if (count != null) return count
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun extractRating(document: Document): String? {
        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data()
        if (jsonLd != null) {
            try {
                val obj = json.parseToJsonElement(jsonLd).jsonObject
                val rating = obj["aggregateRating"]?.jsonObject
                val value = rating?.get("ratingValue")?.jsonPrimitive?.content
                if (value != null) return value
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun buildDescription(document: Document): String {
        val parts = mutableListOf<String>()

        val metaDesc = document.selectFirst("meta[name='description']")?.attr("content")
        if (!metaDesc.isNullOrBlank()) {
            parts.add(metaDesc)
        }

        val tags = extractTags(document)
        if (tags.isNotEmpty()) {
            parts.add("\n\nTags: ${tags.joinToString(", ")}")
        }

        val actors = extractActors(document)
        if (!actors.isNullOrEmpty()) {
            parts.add("\n\nStarring: ${actors.joinToString(", ")}")
        }

        val uploader = extractUploader(document)
        if (!uploader.isNullOrBlank()) {
            parts.add("\n\nUploader: $uploader")
        }

        val views = extractViews(document)
        val rating = extractRating(document)
        if (views != null || rating != null) {
            parts.add("\n\nViews: ${views ?: "?"} | Rating: ${rating ?: "?"}")
        }

        return parts.joinToString("")
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http", ignoreCase = true)) {
            normalized = "https://$normalized"
        }
        val httpsIndex = normalized.indexOf("https://")
        val lastHttpsIndex = normalized.lastIndexOf("https://")
        if (httpsIndex != lastHttpsIndex) {
            normalized = normalized.substring(lastHttpsIndex)
        }
        return normalized
    }

    private fun buildEmbedUrl(videoId: String): String {
        return "https://www.eporner.com/embed/$videoId/"
    }

    private fun String.extractQualityNumber(): Int {
        val digits = filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits.toInt() else 0
    }

    private fun extractJsonObject(text: String): String? {
        var start = text.indexOf('{')
        if (start == -1) return null
        var braceCount = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (!inString) {
                when (c) {
                    '{' -> braceCount++
                    '}' -> braceCount--
                    '"' -> inString = true
                }
            } else {
                when (c) {
                    '"' -> if (!escape) inString = false
                    '\\' -> escape = !escape
                    else -> escape = false
                }
            }
            if (braceCount == 0) {
                return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractVideoFromJson(jsonStr: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val element = json.parseToJsonElement(jsonStr)
            val sources = element.jsonObject["sources"]?.jsonArray
                ?: element.jsonObject["video"]?.jsonObject?.get("sources")?.jsonArray
                ?: element.jsonObject["playlist"]?.jsonArray?.firstOrNull()?.jsonObject?.get("sources")?.jsonArray
            if (sources != null) {
                for (srcElement in sources) {
                    val srcObj = srcElement.jsonObject
                    val src = srcObj["src"]?.jsonPrimitive?.content
                    val type = srcObj["type"]?.jsonPrimitive?.content
                    val label = srcObj["label"]?.jsonPrimitive?.content ?: srcObj["quality"]?.jsonPrimitive?.content
                    if (!src.isNullOrBlank()) {
                        val quality = label ?: when {
                            type?.contains("m3u8") == true -> "HLS"
                            else -> "Unknown"
                        }
                        videos.add(Video(src, quality, src, headers = headers))
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return videos
    }
}
