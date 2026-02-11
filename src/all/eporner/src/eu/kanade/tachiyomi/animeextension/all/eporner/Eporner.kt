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
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    private val apiUrl = "https://www.eporner.com/api/v2"
    override val lang = "all"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val tag = "EpornerExtension"

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-M526BR) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    private var preferences: SharedPreferences? = null

    // ==================== Headers ====================
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "application/json, text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Referer", baseUrl)
            .add("Origin", baseUrl)
            .add("Cache-Control", "no-cache")
            .add("Pragma", "no-cache")
    }

    // ==================== Popular ====================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=top-weekly&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return try {
            val body = response.body.string()
            val apiResponse = json.decodeFromString<ApiSearchResponse>(body)
            val animeList = apiResponse.videos.map { it.toSAnime() }
            val hasNextPage = apiResponse.page < apiResponse.total_pages
            AnimesPage(animeList, hasNextPage)
        } catch (e: Exception) {
            Log.e(tag, "Popular parse error", e)
            AnimesPage(emptyList(), false)
        }
    }

    // ==================== Latest ====================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/video/search/?query=all&page=$page&order=latest&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Search ====================
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
                is CategoryFilter -> if (filter.state != 0) category = filter.toUriPart()
                is DurationFilter -> if (filter.state != 0) duration = filter.toUriPart()
                is QualityFilter -> if (filter.state != 0) quality = filter.toUriPart()
                else -> {}
            }
        }

        val url = "$apiUrl/video/search/?query=$encodedQuery&page=$page&categories=$category&duration=$duration&quality=$quality&thumbsize=big&format=json&per_page=30"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // ==================== Anime Details ====================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers).newBuilder().tag(SAnime::class.java to anime).build()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val tagPair = response.request.tag() as? Pair<*, *>
        val anime = if (tagPair?.first == SAnime::class.java) {
            tagPair.second as? SAnime ?: SAnime.create()
        } else {
            SAnime.create()
        }

        ensureTitleInitialized(anime)

        return try {
            val body = response.body.string()
            val contentType = response.header("Content-Type") ?: ""
            val isLikelyJson = contentType.contains("application/json") || (body.startsWith("{") && body.contains("\"id\""))

            if (isLikelyJson) {
                try {
                    val videoDetail = json.decodeFromString<ApiVideoDetailResponse>(body)
                    return updateAnimeFromApi(anime, videoDetail)
                } catch (_: Exception) {}
            }

            updateAnimeFromEmbedPage(body, anime)
        } catch (_: Exception) {
            ensureAnimeBasics(anime)
        }
    }

    private fun ensureTitleInitialized(anime: SAnime) {
        try {
            anime.title
        } catch (_: kotlin.UninitializedPropertyAccessException) {
            anime.title = "Unknown Title"
        }
    }

    private fun updateAnimeFromApi(anime: SAnime, videoDetail: ApiVideoDetailResponse): SAnime {
        return anime.apply {
            videoDetail.title.takeIf { it.isNotBlank() }?.let { title = it }
            url = "https://www.eporner.com/embed/${videoDetail.id}/"
            if (thumbnail_url.isNullOrBlank()) thumbnail_url = videoDetail.defaultThumb.src.takeIf { it.isNotBlank() }
            if (genre.isNullOrBlank()) genre = videoDetail.keywords.takeIf { it.isNotBlank() }
            val lengthMin = videoDetail.lengthSec / 60
            val lengthSec = videoDetail.lengthSec % 60
            description = "Views: ${videoDetail.views} | Length: ${lengthMin}m ${lengthSec}s"
            status = SAnime.COMPLETED
        }
    }

    private fun updateAnimeFromEmbedPage(html: String, anime: SAnime): SAnime {
        return try {
            val document = Jsoup.parse(html)
            anime.apply {
                val pageTitle = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.takeIf { it.isNotBlank() }
                pageTitle?.let { title = it }

                if (thumbnail_url.isNullOrBlank()) {
                    thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img.thumb")?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: document.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                }

                if (description.isNullOrBlank()) description = "Eporner Video"
                if (genre.isNullOrBlank()) {
                    val tags = document.select("a.tag").mapNotNull { it.text().takeIf { text -> text.isNotBlank() } }
                    if (tags.isNotEmpty()) genre = tags.joinToString(", ")
                }
                status = SAnime.COMPLETED
            }
        } catch (_: Exception) {
            ensureAnimeBasics(anime)
        }
    }

    private fun ensureAnimeBasics(anime: SAnime): SAnime {
        return anime.apply {
            ensureTitleInitialized(this)
            if (description.isNullOrBlank()) description = "Eporner Video"
            status = SAnime.COMPLETED
        }
    }

    // ==================== Episodes ====================
    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return try {
            val embedUrl = response.request.url.toString()
            listOf(
                SEpisode.create().apply {
                    name = "Video"
                    episode_number = 1F
                    url = embedUrl
                },
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ==================== Video Extraction ====================
    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers)
    }

    // -----------------------------------------------------------------
    // METHOD 1: API – permanent direct MP4s (BEST, but sometimes missing all_qualities)
    // -----------------------------------------------------------------
    private fun extractViaApi(embedUrl: String): List<Video> {
        val videoId = Regex("""embed/([^/?]+)""").find(embedUrl)?.groupValues?.get(1)
        if (videoId.isNullOrBlank()) {
            Log.e(tag, "[API] Could not extract video ID from embed URL")
            return emptyList()
        }
        Log.d(tag, "[API] Video ID: $videoId")

        val apiRequestUrl = "$apiUrl/video/search/?id=$videoId&per_page=1&thumbsize=big"
        Log.d(tag, "[API] Request URL: $apiRequestUrl")

        val apiResponse = try {
            client.newCall(GET(apiRequestUrl, headers)).execute()
        } catch (e: Exception) {
            Log.e(tag, "[API] Network error", e)
            return emptyList()
        }

        if (!apiResponse.isSuccessful) {
            Log.e(tag, "[API] HTTP ${apiResponse.code}")
            return emptyList()
        }

        val apiBody = apiResponse.body.string()
        Log.d(tag, "[API] Response body: $apiBody")

        val searchResponse = try {
            json.decodeFromString<ApiSearchResponse>(apiBody)
        } catch (e: Exception) {
            Log.e(tag, "[API] JSON parse error", e)
            return emptyList()
        }

        val video = searchResponse.videos.firstOrNull()
        if (video == null) {
            Log.e(tag, "[API] No video object in response")
            return emptyList()
        }

        val allQualities = video.allQualities
        if (allQualities.isNullOrEmpty()) {
            Log.e(tag, "[API] all_qualities is null or empty")
            return emptyList()
        }

        val headers = headersBuilder()
            .add("Referer", embedUrl)
            .add("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()
        allQualities.forEach { (quality, url) ->
            videos.add(
                Video(
                    url = url,
                    quality = "MP4 • ${quality}p",
                    videoUrl = url,
                    headers = headers,
                ),
            )
        }

        Log.i(tag, "[API] Found ${videos.size} permanent MP4 URLs")
        videos.sort()
        return videos
    }

    // -----------------------------------------------------------------
    // METHOD 2: Direct `dwnld` MP4 URLs – NO EXPIRY, constructed from video ID
    // -----------------------------------------------------------------
    private fun extractDirectDwnldUrls(embedUrl: String): List<Video> {
        val videoId = Regex("""embed/([^/?]+)""").find(embedUrl)?.groupValues?.get(1)
            ?: return emptyList()
        Log.d(tag, "[DWNLD] Video ID: $videoId")

        val qualities = listOf("2160", "1080", "720", "480", "360", "240")
        val baseUrl = "https://static.eporner.com/dwnld"
        val headers = headersBuilder()
            .add("Referer", embedUrl)
            .add("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()

        for (quality in qualities) {
            val url = "$baseUrl/$videoId/${quality}p/${videoId}_${quality}p.mp4"
            try {
                val headRequest = Request.Builder()
                    .url(url)
                    .method("HEAD", null)
                    .headers(headers)
                    .build()
                val headResponse = client.newCall(headRequest).execute()
                if (headResponse.isSuccessful) {
                    videos.add(
                        Video(
                            url = url,
                            quality = "MP4 • ${quality}p",
                            videoUrl = url,
                            headers = headers,
                        ),
                    )
                }
                headResponse.close()
            } catch (_: Exception) {
                // ignore – quality not available
            }
        }

        if (videos.isNotEmpty()) {
            Log.i(tag, "[DWNLD] Found ${videos.size} permanent MP4 URLs")
            videos.sort()
        }
        return videos
    }

    // -----------------------------------------------------------------
    // METHOD 3: `video_sources` JSON (direct MP4, sometimes present)
    // -----------------------------------------------------------------
    private fun extractFromVideoSources(html: String, embedUrl: String): List<Video> {
        val regex = Regex("""var\s+video_sources\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(html) ?: return emptyList()
        val jsonStr = match.groupValues[1]

        return try {
            val sources: Map<String, String> = json.decodeFromString(jsonStr)
            val videos = mutableListOf<Video>()
            val headers = headersBuilder()
                .add("Referer", embedUrl)
                .add("Origin", baseUrl)
                .build()

            sources.forEach { (quality, url) ->
                videos.add(
                    Video(
                        url = url,
                        quality = "MP4 • ${quality}p",
                        videoUrl = url,
                        headers = headers,
                    ),
                )
            }
            videos.sort()
            videos
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse video_sources", e)
            emptyList()
        }
    }

    // -----------------------------------------------------------------
    // METHOD 4: Signed MP4 URLs (temporary – expires after ~30s)
    // -----------------------------------------------------------------
    private fun extractSignedMp4Urls(html: String, embedUrl: String): List<Video> {
        val hash = Regex("""EP\.video\.player\.hash\s*=\s*['"]([^'"]+)""")
            .find(html)?.groupValues?.get(1)
        val vid = Regex("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)""")
            .find(html)?.groupValues?.get(1)
        val expires = Regex("""expires["']?\s*[:=]\s*["']?(\d+)""")
            .find(html)?.groupValues?.get(1)
        val ip = Regex("""ip["']?\s*[:=]\s*["']?([\d\.]+)""")
            .find(html)?.groupValues?.get(1)

        if (hash.isNullOrBlank() || vid.isNullOrBlank() || expires.isNullOrBlank()) {
            return emptyList()
        }

        var numericId = Regex("""video_id["']?\s*:\s*["']?(\d+)""")
            .find(html)?.groupValues?.get(1)
        if (numericId.isNullOrBlank()) {
            numericId = Regex("""/(\d+)_\d+\.jpg""").find(html)?.groupValues?.get(1)
        }
        if (numericId.isNullOrBlank()) return emptyList()

        val cdnServers = listOf(
            "vid-s1-n50-fr-cdn.eporner.com",
            "vid-s2-n50-fr-cdn.eporner.com",
            "vid-s3-n50-fr-cdn.eporner.com",
            "vid-s4-n50-fr-cdn.eporner.com",
            "vid-s13-n50-fr-cdn.eporner.com",
            "vid-s24-n50-fr-cdn.eporner.com",
        )

        val qualities = listOf("2160", "1080", "720", "480", "360", "240")
        val headers = headersBuilder()
            .add("Referer", embedUrl)
            .add("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()

        for (cdn in cdnServers) {
            for (quality in qualities) {
                val url = "https://$cdn/v3/$hash/${expires}_${ip}_$vid/$numericId-$quality.mp4"
                videos.add(
                    Video(
                        url = url,
                        quality = "MP4 • ${quality}p (signed)",
                        videoUrl = url,
                        headers = headers,
                    ),
                )
            }
            break // take first CDN only
        }

        Log.w(tag, "[Signed] Generated ${videos.size} signed MP4 URLs (expire soon!)")
        videos.sort()
        return videos
    }

    // -----------------------------------------------------------------
    // METHOD 5: Direct HLS URL from Video.js player source (MOST RELIABLE HLS METHOD)
    // -----------------------------------------------------------------
    private fun extractHlsFromPlayer(html: String, embedUrl: String): List<Video> {
        val hlsRegex = Regex("""https://[^"'\s]+master\.m3u8[^"'\s]*""")
        val hlsUrl = hlsRegex.find(html)?.value ?: return emptyList()

        Log.d(tag, "[HLS-direct] Found player HLS URL: $hlsUrl")

        val headers = headersBuilder()
            .add("Referer", embedUrl)
            .add("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()
        videos.add(
            Video(
                url = hlsUrl,
                quality = "HLS • Auto",
                videoUrl = hlsUrl,
                headers = headers,
            ),
        )

        // Try to parse master playlist for multiple qualities
        try {
            val res = client.newCall(GET(hlsUrl, headers)).execute()
            if (res.isSuccessful) {
                val masterBody = res.body.string()
                var quality = "Auto"
                masterBody.split("\n").forEach { line ->
                    when {
                        line.startsWith("#EXT-X-STREAM-INF") -> {
                            val q = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)
                            quality = q?.plus("p") ?: "Auto"
                        }
                        line.startsWith("http") -> {
                            videos.add(
                                Video(
                                    url = line,
                                    quality = "HLS • $quality",
                                    videoUrl = line,
                                    headers = headers,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        videos.sort()
        return videos
    }

    // -----------------------------------------------------------------
    // METHOD 6: Constructed HLS master playlist (legacy fallback)
    // -----------------------------------------------------------------
    private fun extractHls(html: String, embedUrl: String): List<Video> {
        val hash = Regex("""EP\.video\.player\.hash\s*=\s*['"]([^'"]+)""")
            .find(html)?.groupValues?.get(1)
        val vid = Regex("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)""")
            .find(html)?.groupValues?.get(1)
        if (hash.isNullOrBlank() || vid.isNullOrBlank()) return emptyList()

        var numericId = Regex("""video_id["']?\s*:\s*["']?(\d+)""")
            .find(html)?.groupValues?.get(1)
        if (numericId.isNullOrBlank()) {
            numericId = Regex("""/(\d+)_\d+\.jpg""").find(html)?.groupValues?.get(1)
        }
        if (numericId.isNullOrBlank()) return emptyList()

        val expires = Regex("""expires["']?\s*[:=]\s*["']?(\d+)""")
            .find(html)?.groupValues?.get(1)
        val ip = Regex("""ip["']?\s*[:=]\s*["']?([\d\.]+)""")
            .find(html)?.groupValues?.get(1)

        val cdnServers = listOf(
            "dash-s1-c50-fr-cdn.eporner.com",
            "dash-s2-c50-fr-cdn.eporner.com",
            "dash-s3-c50-fr-cdn.eporner.com",
            "dash-s4-c50-fr-cdn.eporner.com",
            "dash-s13-c50-fr-cdn.eporner.com",
            "dash-s24-c50-fr-cdn.eporner.com",
        )

        val headers = headersBuilder()
            .add("Referer", embedUrl)
            .add("Origin", baseUrl)
            .build()

        var masterBody: String? = null
        var masterUrl: String? = null

        for (cdn in cdnServers) {
            val url = StringBuilder()
                .append("https://$cdn/hls/v5/")
                .append("$numericId-,240p,360p,480p,720p,.mp4.urlset/master.m3u8")
                .append("?hash=$hash")
                .apply {
                    if (!expires.isNullOrBlank()) append("&expires=$expires")
                    if (!ip.isNullOrBlank()) append("&ip=$ip")
                }
                .toString()

            try {
                val res = client.newCall(GET(url, headers)).execute()
                if (res.isSuccessful) {
                    masterBody = res.body.string()
                    masterUrl = url
                    break
                }
            } catch (_: Exception) {}
        }

        if (masterBody.isNullOrBlank() || masterUrl == null) return emptyList()

        val videos = mutableListOf<Video>()
        var quality = "Auto"

        masterBody.split("\n").forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val q = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)
                    quality = q?.plus("p") ?: "Auto"
                }
                line.startsWith("http") -> {
                    videos.add(
                        Video(
                            url = line,
                            quality = "HLS • $quality",
                            videoUrl = line,
                            headers = headers,
                        ),
                    )
                }
            }
        }

        if (videos.isEmpty()) {
            videos.add(
                Video(
                    url = masterUrl,
                    quality = "HLS • Auto",
                    videoUrl = masterUrl,
                    headers = headers,
                ),
            )
        }

        videos.sort()
        return videos
    }

    // -----------------------------------------------------------------
    // ✅ MAIN – tries methods in order of reliability
    // -----------------------------------------------------------------
    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val embedUrl = response.request.url.toString()
        Log.d(tag, "Embed URL: $embedUrl")

        // 1️⃣ API (permanent MP4) – best, but sometimes missing all_qualities
        extractViaApi(embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.i(tag, "✅ Using API permanent MP4 (${videos.size} qualities)")
                return videos
            }
        }

        // 2️⃣ Direct dwnld URLs (permanent MP4) – constructed from video ID
        extractDirectDwnldUrls(embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.i(tag, "✅ Using direct dwnld MP4 (${videos.size} qualities)")
                return videos
            }
        }

        // 3️⃣ video_sources JSON (permanent MP4) – not always present
        extractFromVideoSources(html, embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.i(tag, "✅ Using video_sources JSON (${videos.size} qualities)")
                return videos
            }
        }

        // 4️⃣ Signed MP4 URLs (expire after ~30s) – temporary fallback
        extractSignedMp4Urls(html, embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.w(tag, "⚠️ Using signed MP4 URLs – WILL EXPIRE SOON!")
                return videos
            }
        }

        // 5️⃣ DIRECT HLS URL from player (MOST RELIABLE HLS METHOD)
        extractHlsFromPlayer(html, embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.i(tag, "✅ Using HLS URL from player (${videos.size} streams)")
                return videos
            }
        }

        // 6️⃣ Constructed HLS master playlist (legacy fallback)
        Log.w(tag, "⚠️ Falling back to constructed HLS")
        extractHls(html, embedUrl).let { videos ->
            if (videos.isNotEmpty()) {
                Log.i(tag, "✅ Using constructed HLS (${videos.size} streams)")
                return videos
            }
        }

        Log.e(tag, "❌ No videos found with any method")
        return emptyList()
    }

    override fun videoUrlParse(response: Response): String {
        return videoListParse(response).firstOrNull()?.videoUrl ?: ""
    }

    // ==================== Settings ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences = android.preference.PreferenceManager.getDefaultSharedPreferences(screen.context)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            val currentValue = preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            setValueIndex(QUALITY_LIST.indexOf(currentValue).coerceAtLeast(0))
            summary = if (currentValue == "best") "Best available quality" else currentValue
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences?.edit()?.putString(PREF_QUALITY_KEY, selected)?.apply()
                summary = if (selected == "best") "Best available quality" else selected
                true
            }
        }.also(
            screen::addPreference,
        )

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
                summary = selected
                true
            }
        }.also(
            screen::addPreference,
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val qualityPref = try {
            preferences?.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        } catch (_: Exception) {
            PREF_QUALITY_DEFAULT
        }

        return sortedWith(
            compareByDescending<Video> {
                when {
                    qualityPref == "best" -> it.quality.replace("p", "").toIntOrNull() ?: 0
                    it.quality.contains(qualityPref, ignoreCase = false) -> 1000
                    else -> 0
                }
            },
        )
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        DurationFilter(),
        QualityFilter(),
    )

    // ==================== Filter Classes ====================
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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p")

        private const val PREF_SORT_KEY = "default_sort"
        private const val PREF_SORT_DEFAULT = "top-weekly"
        private val SORT_LIST = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated")
    }

    // ==================== API Data Classes ====================
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
    )

    @Serializable
    private data class ApiVideo(
        @SerialName("id") val id: String,
        @SerialName("title") val title: String,
        @SerialName("keywords") val keywords: String,
        @SerialName("url") val url: String,
        @SerialName("embed") val embed: String,
        @SerialName("default_thumb") val defaultThumb: ApiThumbnail,
        @SerialName("all_qualities") val allQualities: Map<String, String>? = null,
    ) {
        fun toSAnime(): SAnime = SAnime.create().apply {
            title = this@ApiVideo.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
            url = this@ApiVideo.embed
            thumbnail_url = this@ApiVideo.defaultThumb.src.takeIf { it.isNotBlank() }
            genre = this@ApiVideo.keywords.takeIf { it.isNotBlank() }
            status = SAnime.COMPLETED
        }
    }

    @Serializable
    private data class ApiThumbnail(
        @SerialName("src") val src: String,
        @SerialName("width") val width: Int,
        @SerialName("height") val height: Int,
    )
}
