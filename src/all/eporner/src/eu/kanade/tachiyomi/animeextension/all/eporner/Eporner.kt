package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val tag = "EpornerExtension"

    // ==================== ENHANCED HTTP CLIENT WITH INTELLIGENT COOKIE MANAGEMENT ====================
    private class IntelligentCookieJar : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
        private val domainCookies = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val domain = url.host
            val existingCookies = domainCookies[domain] ?: mutableListOf()

            cookies.forEach { newCookie ->
                val existingIndex = existingCookies.indexOfFirst { it.name == newCookie.name }
                if (existingIndex != -1) {
                    existingCookies[existingIndex] = newCookie
                } else {
                    existingCookies.add(newCookie)
                }
            }

            domainCookies[domain] = existingCookies
            cookieStore[url.toString()] = cookies

            Log.d("CookieJar", "Saved ${cookies.size} cookies for $domain: ${cookies.map { it.name }}")
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            // Return cookies for this specific domain
            val domainCookiesList = domainCookies[url.host] ?: emptyList()

            // Also check for parent domain cookies
            val parentDomain = url.host.substringAfterLast(".", "").let {
                if (it.isNotEmpty()) ".$it" else ""
            }

            val allCookies = mutableListOf<Cookie>()
            allCookies.addAll(domainCookiesList)

            if (parentDomain.isNotEmpty()) {
                domainCookies.entries.forEach { (domain, cookies) ->
                    if (domain.endsWith(parentDomain)) {
                        allCookies.addAll(cookies)
                    }
                }
            }

            // Filter out expired cookies
            val validCookies = allCookies.filter { !it.expiresAt().let { exp -> exp != 0L && exp < System.currentTimeMillis() } }

            if (validCookies.isNotEmpty()) {
                Log.d("CookieJar", "Loaded ${validCookies.size} cookies for ${url.host}: ${validCookies.map { it.name }}")
            }

            return validCookies.distinctBy { it.name }
        }

        fun clearCookies() {
            cookieStore.clear()
            domainCookies.clear()
            Log.d("CookieJar", "All cookies cleared")
        }
    }

    private val cookieJar = IntelligentCookieJar()

    override val client: OkHttpClient = network.client.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()

            // Add delay between requests to mimic human behavior
            Thread.sleep((500 + Math.random() * 1500).toLong())

            // Clone request with updated headers
            val requestBuilder = originalRequest.newBuilder()

            // Ensure User-Agent is always present
            if (originalRequest.header("User-Agent") == null) {
                requestBuilder.header("User-Agent", USER_AGENT)
            }

            // Add cache control headers
            requestBuilder.header("Cache-Control", "no-cache")
            requestBuilder.header("Pragma", "no-cache")

            val newRequest = requestBuilder.build()
            val response = chain.proceed(newRequest)

            // Log response details for debugging
            Log.d(tag, "Request to: ${newRequest.url}")
            Log.d(tag, "Response code: ${response.code}")

            response
        }
        .build()

    // ==================== ENHANCED HEADERS MANAGEMENT ====================
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"

    private fun initializeVideoSession(videoId: String): String {
        try {
            Log.d(tag, "Initializing session for video: $videoId")

            // Step 1: Visit main video page to establish base cookies
            val videoPageUrl = "$baseUrl/video-$videoId/"
            val videoRequest = GET(videoPageUrl, headersBuilder().build())

            client.newCall(videoRequest).execute().use { response ->
                // Just need cookies, not response body
                Log.d(tag, "Video page visited, status: ${response.code}")
            }

            // Realistic delay
            Thread.sleep(1200)

            // Step 2: Visit embed page with proper referer
            val embedUrl = "$baseUrl/embed/$videoId/"
            val embedRequest = GET(embedUrl, headersBuilder()
                .add("Referer", videoPageUrl)
                .add("Sec-Fetch-Dest", "iframe")
                .add("Sec-Fetch-Mode", "navigate")
                .add("Sec-Fetch-Site", "cross-site")
                .add("Upgrade-Insecure-Requests", "1")
                .build())

            return client.newCall(embedRequest).execute().use { response ->
                val html = response.body!!.string()
                Log.d(tag, "Embed page loaded, status: ${response.code}, length: ${html.length}")
                html
            }

        } catch (e: Exception) {
            Log.e(tag, "Session initialization failed", e)
            throw e
        }
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "gzip, deflate, br, zstd")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Cache-Control", "max-age=0")
            .add("sec-ch-ua", """Not A(Brand";v="99", "Microsoft Edge";v="121", "Chromium";v="121""")
            .add("sec-ch-ua-mobile", "?0")
            .add("sec-ch-ua-platform", """Windows""")
    }

    private fun apiHeadersBuilder(referer: String): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "gzip, deflate, br, zstd")
            .add("Referer", referer)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Origin", baseUrl)
            .add("Sec-Fetch-Dest", "empty")
            .add("Sec-Fetch-Mode", "cors")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Connection", "keep-alive")
            .add("sec-ch-ua", """Not A(Brand";v="99", "Microsoft Edge";v="121", "Chromium";v="121""")
            .add("sec-ch-ua-mobile", "?0")
            .add("sec-ch-ua-platform", """Windows""")
    }

    // ==================== POPULAR ANIME ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Popular request: $url")
        return GET(url, headersBuilder().build())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val body = response.body.string()
            val apiResponse = json.decodeFromString<ApiSearchResponse>(body)
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.total_pages

            Log.d(tag, "Popular parse: ${animeList.size} videos, hasNext: $hasNextPage")
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error", e)
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== LATEST UPDATES ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Latest request: $url")
        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== SEARCH ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = if (query.isNotBlank()) {
            URLEncoder.encode(query, "UTF-8")
        } else {
            "all"
        }

        var category = "all"
        var duration = "0"
        var quality = "0"

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state != 0) category = filter.toUriPart()
                }
                is DurationFilter -> {
                    if (filter.state != 0) duration = filter.toUriPart()
                }
                is QualityFilter -> {
                    if (filter.state != 0) quality = filter.toUriPart()
                }
            }
        }

        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&categories=$category&duration=$duration&quality=$quality&thumbsize=big&format=json&per_page=30"
        Log.d(tag, "Search request: $url")
        return GET(url, headersBuilder().build())
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== ANIME DETAILS ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        Log.d(tag, "Details request: $url")
        return GET(url, headersBuilder().build())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val url = response.request.url.toString()

        return try {
            val body = response.body.string()
            val document = Jsoup.parse(body)

            // Extract title
            val title = document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?: "Unknown Title"

            // Extract thumbnail
            val thumbnail = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.thumb")?.attr("src")
                ?: document.selectFirst("img#main-video")?.attr("src")

            // Extract statistics
            val stats = mutableListOf<String>()
            document.select(".stats li").forEach { li ->
                stats.add(li.text().trim())
            }

            // Extract tags
            val tags = document.select("a.tag").mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }

            anime.apply {
                this.title = title
                this.thumbnail_url = thumbnail
                this.url = url
                this.genre = if (tags.isNotEmpty()) tags.joinToString(", ") else null
                this.description = if (stats.isNotEmpty()) stats.joinToString(" • ") else "Eporner Video"
                this.status = SAnime.COMPLETED
            }
        } catch (e: Exception) {
            Log.e(tag, "Details parse error", e)
            anime.apply {
                this.title = "Eporner Video"
                this.url = url
                this.status = SAnime.COMPLETED
            }
        }
    }

    // ==================== COMPLETELY REWRITTEN VIDEO EXTRACTION ====================
    override fun videoListParse(response: Response): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val requestUrl = response.request.url.toString()
            Log.d(tag, "Starting video extraction for URL: $requestUrl")

            // Extract video ID
            val videoId = extractVideoId(requestUrl)
            if (videoId.isNullOrEmpty()) {
                Log.e(tag, "Failed to extract video ID from URL: $requestUrl")
                return emptyList()
            }

            Log.d(tag, "Extracted video ID: $videoId")

            // METHOD 1: Primary XHR API method (most reliable)
            val xhrVideos = extractViaXhrApi(videoId)
            if (xhrVideos.isNotEmpty()) {
                Log.d(tag, "XHR method successful: ${xhrVideos.size} videos found")
                videos.addAll(xhrVideos)
            }

            // METHOD 2: Alternative API endpoint (fallback)
            if (videos.isEmpty()) {
                Log.d(tag, "Trying alternative API method...")
                val apiVideos = extractViaAlternativeApi(videoId)
                if (apiVideos.isNotEmpty()) {
                    Log.d(tag, "Alternative API successful: ${apiVideos.size} videos found")
                    videos.addAll(apiVideos)
                }
            }

            // METHOD 3: Direct HTML parsing (last resort)
            if (videos.isEmpty()) {
                Log.d(tag, "Trying direct HTML parsing...")
                val htmlVideos = extractDirectFromPage(videoId)
                if (htmlVideos.isNotEmpty()) {
                    Log.d(tag, "HTML parsing successful: ${htmlVideos.size} videos found")
                    videos.addAll(htmlVideos)
                }
            }

            // Remove duplicates and sort by quality
            val uniqueVideos = videos.distinctBy { it.videoUrl }
                .sortedByDescending { video ->
                    val qualityNum = video.quality.replace("p", "").toIntOrNull() ?: 0
                    qualityNum
                }

            Log.d(tag, "Final result: ${uniqueVideos.size} unique videos")
            return uniqueVideos

        } catch (e: Exception) {
            Log.e(tag, "Video extraction completely failed", e)
            return emptyList()
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""embed/([a-zA-Z0-9_-]+)/?$"""),
            Regex("""/video-([a-zA-Z0-9_-]+)/?$"""),
            Regex("""video/([a-zA-Z0-9_-]+)/?$"""),
            Regex("""\.com/([a-zA-Z0-9_-]+)/?$""")
        )

        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }

        // Try to extract from query parameters
        if (url.contains("id=")) {
            val idPattern = Regex("""[?&]id=([a-zA-Z0-9_-]+)""")
            idPattern.find(url)?.groupValues?.get(1)?.let { return it }
        }

        return null
    }

    private fun extractViaXhrApi(videoId: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            // Step 1: Initialize session and get embed page
            val embedHtml = initializeVideoSession(videoId)
            val embedUrl = "$baseUrl/embed/$videoId/"

            // Step 2: Extract hash from embed page
            val hash = extractHashFromScripts(embedHtml)
            if (hash.isNullOrEmpty()) {
                Log.e(tag, "Hash extraction failed for video $videoId")
                return emptyList()
            }

            Log.d(tag, "Successfully extracted hash: $hash")

            // Step 3: Build XHR URL
            val timestamp = System.currentTimeMillis()
            val xhrUrl = "$baseUrl/xhr/video/$videoId?" +
                "hash=$hash" +
                "&domain=www.eporner.com" +
                "&device=desktop" +
                "&device_type=desktop" +
                "&pixelRatio=1.25" +
                "&playerWidth=1920" +
                "&playerHeight=1080" +
                "&fallback=true" +
                "&embed=true" +
                "&supportedFormats=hls,dash,mp4,webm" +
                "&autoplay=false" +
                "&muted=false" +
                "&_=$timestamp"

            Log.d(tag, "XHR URL constructed")

            // Step 4: Make XHR request
            val xhrRequest = GET(xhrUrl, apiHeadersBuilder(embedUrl).build())

            // Add delay before XHR request
            Thread.sleep((800 + Math.random() * 1200).toLong())

            val xhrResponse = client.newCall(xhrRequest).execute()
            val xhrBody = xhrResponse.body!!.string()
            xhrResponse.close()

            Log.d(tag, "XHR Response received, length: ${xhrBody.length}")

            // Step 5: Parse XHR response
            val jsonResponse = json.decodeFromString<XhrApiResponse>(xhrBody)

            // Extract MP4 videos
            jsonResponse.mp4?.forEach { (quality, url) ->
                if (!quality.isNullOrEmpty() && !url.isNullOrEmpty()) {
                    val cleanUrl = url.replace("\\/", "/")
                    val videoHeaders = videoHeaders(embedUrl, cleanUrl)
                    videos.add(Video(cleanUrl, quality, cleanUrl, videoHeaders))
                    Log.d(tag, "Added MP4: $quality")
                }
            }

            // Extract HLS videos
            jsonResponse.hls?.url?.let { hlsUrl ->
                val cleanHlsUrl = hlsUrl.replace("\\/", "/")
                val hlsVideos = extractHlsVariants(cleanHlsUrl, embedUrl)
                videos.addAll(hlsVideos)
                Log.d(tag, "Added ${hlsVideos.size} HLS variants")
            }

            // Extract DASH videos
            jsonResponse.dash?.url?.let { dashUrl ->
                val cleanDashUrl = dashUrl.replace("\\/", "/")
                val videoHeaders = videoHeaders(embedUrl, cleanDashUrl)
                videos.add(Video(cleanDashUrl, "DASH", cleanDashUrl, videoHeaders))
                Log.d(tag, "Added DASH stream")
            }

        } catch (e: Exception) {
            Log.e(tag, "XHR API extraction failed for video $videoId", e)
        }

        return videos
    }

    private fun extractHashFromScripts(html: String): String? {
        // Try multiple patterns in order of likelihood

        // Pattern 1: Look in window.config
        val configPattern = Regex("""window\.config\s*=\s*\{[^}]+hash['"]?\s*:\s*['"]([a-zA-Z0-9]{24})['"]""")
        configPattern.find(html)?.groupValues?.get(1)?.let {
            Log.d(tag, "Found hash in window.config")
            return it
        }

        // Pattern 2: Look for hash variable assignment
        val varPatterns = listOf(
            Regex("""hash\s*=\s*['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""var\s+hash\s*=\s*['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""let\s+hash\s*=\s*['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""const\s+hash\s*=\s*['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""['"]hash['"]\s*:\s*['"]([a-zA-Z0-9]{24})['"]""")
        )

        for (pattern in varPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.let {
                Log.d(tag, "Found hash in variable assignment")
                return it
            }
        }

        // Pattern 3: Look in data attributes
        val dataPatterns = listOf(
            Regex("""data-hash=['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""data-video-hash=['"]([a-zA-Z0-9]{24})['"]"""),
            Regex("""hash=['"]([a-zA-Z0-9]{24})['"]""")
        )

        for (pattern in dataPatterns) {
            pattern.find(html)?.groupValues?.get(1)?.let {
                Log.d(tag, "Found hash in data attribute")
                return it
            }
        }

        // Pattern 4: Look in XHR URLs
        val xhrPattern = Regex("""/xhr/video/[^?]+\?[^'"]*hash=([a-zA-Z0-9]{24})""")
        xhrPattern.find(html)?.groupValues?.get(1)?.let {
            Log.d(tag, "Found hash in XHR URL")
            return it
        }

        // Pattern 5: Generic search for 24-character hash
        val genericPattern = Regex("""['"]([a-zA-Z0-9]{24})['"]""")
        val allHashes = genericPattern.findAll(html).map { it.groupValues[1] }.toList()

        // Filter likely hashes (not in common words, not part of other patterns)
        val likelyHashes = allHashes.filter { hash ->
            !html.contains("$hash.jpg") &&
            !html.contains("$hash.png") &&
            !html.contains("$hash.mp4") &&
            hash.any { it.isDigit() } &&
            hash.any { it.isLetter() }
        }

        if (likelyHashes.isNotEmpty()) {
            Log.d(tag, "Found hash via generic pattern")
            return likelyHashes.first()
        }

        Log.e(tag, "No hash found in HTML")
        return null
    }

    private fun extractViaAlternativeApi(videoId: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val apiUrl = "$apiUrl/video/id/$videoId/?format=json&thumbsize=large"
            val apiRequest = GET(apiUrl, apiHeadersBuilder("$baseUrl/video-$videoId/").build())

            // Add delay
            Thread.sleep((600 + Math.random() * 900).toLong())

            val apiResponse = client.newCall(apiRequest).execute()
            val apiBody = apiResponse.body!!.string()
            apiResponse.close()

            Log.d(tag, "Alternative API response length: ${apiBody.length}")

            val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(apiBody)

            videoDetail.sources?.forEach { source ->
                source.src?.let { src ->
                    if (src.isNotBlank()) {
                        val quality = source.label ?: extractQualityFromUrl(src)
                        val videoHeaders = videoHeaders("$baseUrl/video-$videoId/", src)
                        videos.add(Video(src, quality, src, videoHeaders))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Alternative API extraction failed", e)
        }

        return videos
    }

    private fun extractDirectFromPage(videoId: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            val videoPageUrl = "$baseUrl/video-$videoId/"
            val request = GET(videoPageUrl, headersBuilder().build())

            // Add delay
            Thread.sleep((700 + Math.random() * 1100).toLong())

            val response = client.newCall(request).execute()
            val html = response.body!!.string()
            response.close()

            Log.d(tag, "Direct page HTML length: ${html.length}")

            // Look for video sources
            val sourcePatterns = listOf(
                Regex("""(https?://[^"' ]+\.mp4[^"' ]*)"""),
                Regex("""src:\s*['"](https?://[^"']+\.mp4)['"]"""),
                Regex("""source\s+src=['"](https?://[^"']+\.mp4)['"]"""),
                Regex("""(https?://[^"' ]+\.m3u8[^"' ]*)"""),
                Regex("""hlsUrl:\s*['"](https?://[^"']+)['"]""")
            )

            for (pattern in sourcePatterns) {
                pattern.findAll(html).forEach { match ->
                    val url = match.value
                    if (url.contains("eporner.com") && !url.contains("thumb") && !url.contains("preview")) {
                        val quality = extractQualityFromUrl(url)
                        val videoHeaders = videoHeaders(videoPageUrl, url)
                        videos.add(Video(url, quality, url, videoHeaders))
                        Log.d(tag, "Found direct URL: $quality")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Direct page extraction failed", e)
        }

        return videos
    }

    private fun extractHlsVariants(masterUrl: String, referer: String): List<Video> {
        val videos = mutableListOf<Video>()

        try {
            Log.d(tag, "Fetching HLS master: $masterUrl")

            val request = GET(masterUrl, videoHeaders(referer, masterUrl))
            val response = client.newCall(request).execute()
            val playlistContent = response.body!!.string()
            response.close()

            // Parse variant streams
            val variantPattern = Regex("""#EXT-X-STREAM-INF:[^\n]*RESOLUTION=(\d+)x(\d+)[^\n]*\n([^\n]+)""",
                RegexOption.DOT_MATCHES_ALL)

            val matches = variantPattern.findAll(playlistContent)

            matches.forEach { match ->
                val width = match.groupValues[1]
                val height = match.groupValues[2]
                var streamPath = match.groupValues[3].trim()

                // Construct absolute URL if relative
                if (!streamPath.startsWith("http")) {
                    val baseUrl = masterUrl.substringBeforeLast("/")
                    streamPath = "$baseUrl/$streamPath"
                }

                val quality = "${height}p"
                val headers = videoHeaders(referer, streamPath)
                videos.add(Video(streamPath, quality, streamPath, headers))

                Log.d(tag, "Added HLS variant: $quality")
            }

            // If no variants, add master URL
            if (videos.isEmpty()) {
                val headers = videoHeaders(referer, masterUrl)
                videos.add(Video(masterUrl, "HLS", masterUrl, headers))
            }

        } catch (e: Exception) {
            Log.e(tag, "HLS extraction failed", e)
        }

        return videos
    }

    private fun extractQualityFromUrl(url: String): String {
        val patterns = listOf(
            Regex("""/(\d+)p/"""),
            Regex("""_(\d+)p\.mp4"""),
            Regex("""\.(\d+)\.mp4"""),
            Regex("""quality=(\d+)"""),
            Regex("""/q(\d+)/"""),
            Regex("""/hq(\d+)/"""),
            Regex("""-(\d+)p\."""),
            Regex("""_(\d+)_""")
        )

        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let {
                return "${it}p"
            }
        }

        // Default detection
        return when {
            url.contains("1080") || url.contains("/1080/") || url.contains("_1080_") -> "1080p"
            url.contains("720") || url.contains("/720/") || url.contains("_720_") -> "720p"
            url.contains("480") || url.contains("/480/") || url.contains("_480_") -> "480p"
            url.contains("360") || url.contains("/360/") || url.contains("_360_") -> "360p"
            url.contains("240") || url.contains("/240/") || url.contains("_240_") -> "240p"
            else -> "SD"
        }
    }

    private fun videoHeaders(referer: String, videoUrl: String): Headers {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", referer)
            .add("Origin", "https://www.eporner.com")
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept-Encoding", "identity")
            .add("Connection", "keep-alive")
            .add("Range", "bytes=0-")
            .add("Sec-Fetch-Dest", "video")
            .add("Sec-Fetch-Mode", "no-cors")
            .add("Sec-Fetch-Site", "cross-site")
            .build()
    }

    // ==================== REQUIRED METHODS ====================
    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headersBuilder().build())
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headersBuilder().build())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString()
                date_upload = System.currentTimeMillis()
            }
        )
    }

    // ==================== SETTINGS & PREFERENCES ====================
    private var preferences: SharedPreferences? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences = android.preference.PreferenceManager.getDefaultSharedPreferences(screen.context)

        // Video quality preference
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)

            val currentValue = preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            setValueIndex(QUALITY_LIST.indexOf(currentValue).coerceAtLeast(0))
            summary = when (currentValue) {
                "best" -> "Best available quality"
                else -> currentValue
            }

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_QUALITY_KEY, selected)?.apply()
                this.summary = when (selected) {
                    "best" -> "Best available quality"
                    else -> selected
                }
                true
            }
        }.also(screen::addPreference)

        // Sort order preference
        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default sort order"
            entries = SORT_LIST
            entryValues = SORT_LIST
            setDefaultValue(PREF_SORT_DEFAULT)

            val currentValue = preferences?.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT) ?: PREF_SORT_DEFAULT
            setValueIndex(SORT_LIST.indexOf(currentValue).coerceAtLeast(0))
            summary = currentValue

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_SORT_KEY, selected)?.apply()
                this.summary = selected
                true
            }
        }.also(screen::addPreference)

        // Cookie management
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_COOKIES
            title = "Enable cookies (required for videos)"
            summary = "Must be enabled for video playback"
            setDefaultValue(true)
            isChecked = preferences?.getBoolean(PREF_ENABLE_COOKIES, true) ?: true

            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                preferences?.edit()?.putBoolean(PREF_ENABLE_COOKIES, enabled)?.apply()
                if (!enabled) {
                    cookieJar.clearCookies()
                }
                true
            }
        }.also(screen::addPreference)

        // Request delay
        ListPreference(screen.context).apply {
            key = PREF_REQUEST_DELAY
            title = "Request delay (anti-bot)"
            entries = DELAY_LIST
            entryValues = DELAY_VALUES
            setDefaultValue(PREF_REQUEST_DELAY_DEFAULT)

            val currentValue = preferences?.getString(PREF_REQUEST_DELAY, PREF_REQUEST_DELAY_DEFAULT) ?: PREF_REQUEST_DELAY_DEFAULT
            setValueIndex(DELAY_VALUES.indexOf(currentValue).coerceAtLeast(0))
            summary = DELAY_LIST[DELAY_VALUES.indexOf(currentValue).coerceAtLeast(0)]

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_REQUEST_DELAY, selected)?.apply()
                this.summary = DELAY_LIST[DELAY_VALUES.indexOf(selected).coerceAtLeast(0)]
                true
            }
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val qualityPref = try {
            preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        } catch (e: Exception) {
            PREF_QUALITY_DEFAULT
        }

        return if (qualityPref == "best") {
            sortedByDescending { it.quality.replace("p", "").toIntOrNull() ?: 0 }
        } else {
            val prefQualityNum = qualityPref.replace("p", "").toIntOrNull() ?: 720
            sortedWith(
                compareByDescending<Video> { video ->
                    val videoQuality = video.quality.replace("p", "").toIntOrNull() ?: 0
                    when {
                        videoQuality == prefQualityNum -> 1000
                        videoQuality < prefQualityNum -> videoQuality
                        else -> videoQuality - (videoQuality - prefQualityNum) * 10
                    }
                }
            )
        }
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        DurationFilter(),
        QualityFilter(),
    )

    // ==================== FILTER CLASSES ====================
    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter : UriPartFilter(
        "Category",
        arrayOf(
            Pair("All", "all"),
            Pair("Anal", "anal"),
            Pair("Asian", "asian"),
            Pair("Big Dick", "big-dick"),
            Pair("Blowjob", "blowjob"),
            Pair("Brunette", "brunette"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Doggystyle", "doggystyle"),
            Pair("Ebony", "ebony"),
            Pair("Facial", "facial"),
            Pair("Gangbang", "gangbang"),
            Pair("HD", "hd"),
            Pair("Interracial", "interracial"),
            Pair("Lesbian", "lesbian"),
            Pair("Masturbation", "masturbation"),
            Pair("Mature", "mature"),
            Pair("Milf", "milf"),
            Pair("Teen", "teen"),
        ),
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            Pair("Any", "0"),
            Pair("10+ min", "10"),
            Pair("20+ min", "20"),
            Pair("30+ min", "30"),
        ),
    )

    private class QualityFilter : UriPartFilter(
        "Quality",
        arrayOf(
            Pair("Any", "0"),
            Pair("HD 1080", "1080"),
            Pair("HD 720", "720"),
            Pair("HD 480", "480"),
        ),
    )

    // ==================== DATA CLASSES ====================
    @Serializable
    private data class ApiSearchResponse(
        @SerialName("videos") val videos: List<ApiVideo>,
        @SerialName("page") val page: Int,
        @SerialName("total_pages") val total_pages: Int,
    )

    @Serializable
    private data class ApiVideoDetailResponse(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("views") val views: Long,
        @SerialName("url") val url: String,
        @SerialName("added") val added: String,
        @SerialName("length_sec") val lengthSec: Int,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
        @SerialName("thumbs") val thumbs: List<ApiThumbnail>,
        @SerialName("sources") val sources: List<VideoSource>? = null,
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            this.title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            this.url = this@ApiVideo.embed
            this.thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }
            this.genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }
            this.status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )

    @Serializable
    private data class VideoSource(
        @SerialName("src") val src: String? = null,
        @SerialName("type") val type: String? = null,
        @SerialName("label") val label: String? = null,
    )

    @Serializable
    private data class XhrApiResponse(
        @SerialName("success") val success: Boolean? = null,
        @SerialName("mp4") val mp4: Map<String, String?>? = null,
        @SerialName("hls") val hls: HlsSource? = null,
        @SerialName("dash") val dash: DashSource? = null,
    )

    @Serializable
    private data class HlsSource(
        @SerialName("url") val url: String? = null,
        @SerialName("type") val type: String? = null,
    )

    @Serializable
    private data class DashSource(
        @SerialName("url") val url: String? = null,
        @SerialName("type") val type: String? = null,
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "best"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p", "240p", "SD")

        private const val PREF_SORT_KEY = "default_sort"
        private const val PREF_SORT_DEFAULT = "top-weekly"
        private val SORT_LIST = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated")

        private const val PREF_ENABLE_COOKIES = "enable_cookies"

        private const val PREF_REQUEST_DELAY = "request_delay"
        private const val PREF_REQUEST_DELAY_DEFAULT = "medium"
        private val DELAY_LIST = arrayOf("Minimum (fastest)", "Low", "Medium (recommended)", "High", "Maximum (slowest)")
        private val DELAY_VALUES = arrayOf("min", "low", "medium", "high", "max")
    }
}
