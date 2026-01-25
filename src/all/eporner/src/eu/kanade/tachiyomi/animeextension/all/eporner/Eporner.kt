package eu.kanade.tachiyomi.animeextension.all.eporner

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
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Headers for video requests
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // Eporner uses 0-based pagination for popular
        val pageNum = if (page > 1) page - 1 else 0
        return GET("$baseUrl/hd-porn/$pageNum/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Eporner structure: each video in div.eporner-block
        val elements = document.select("div.mb, div.eporner-block, div.video")

        val animeList = elements.map { element ->
            SAnime.create().apply {
                // Eporner links are like: /video-abc123/title/
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                title = element.selectFirst("a")?.attr("title")
                    ?: element.selectFirst("img")?.attr("alt")
                    ?: element.selectFirst("a span")?.text()
                    ?: "Unknown"
                thumbnail_url = element.selectFirst("img")?.attr("src")
                    ?: element.selectFirst("img")?.attr("data-src")
            }
        }

        // Check for next page - Eporner usually has pagination at bottom
        val hasNextPage = document.select("a[rel=next], a.next, li.next").isNotEmpty() ||
            document.select("div.pagination a:contains(Next)").isNotEmpty()

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val pageNum = if (page > 1) page - 1 else 0
        return GET("$baseUrl/latest/$pageNum/", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response) // Same structure as popular
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val pageNum = if (page > 1) page - 1 else 0

        // Check for category filter
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected

        return if (query.isNotBlank()) {
            // Text search
            GET("$baseUrl/search/${query.encodeUtf8()}/$pageNum/", headers)
        } else if (category != null) {
            // Category filter
            GET("$baseUrl/category/${category}/$pageNum/", headers)
        } else {
            // Default to popular
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    private fun String.encodeUtf8() = java.net.URLEncoder.encode(this, "UTF-8")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            // Title from h1
            title = document.selectFirst("h1")?.text()
                ?: document.selectFirst(".title")?.text()
                ?: "Unknown"

            // Metadata from info section
            val infoSection = document.selectFirst("div.video-details, div.info")
            infoSection?.let { info ->
                // Categories/tags
                genre = info.select("a[href*=/category/], a[href*=/tag/]")
                    .joinToString { it.text() }

                // Pornstars/actors
                artist = info.select("a[href*=/pornstar/]")
                    .joinToString { it.text() }

                // Additional info
                val details = mutableListOf<String>()
                info.select("p, div:not(:has(a))").forEach { elem ->
                    val text = elem.text().trim()
                    if (text.isNotBlank() && !text.contains("http")) {
                        details.add(text)
                    }
                }
                if (details.isNotEmpty()) {
                    description = details.joinToString("\n")
                }
            }

            // Thumbnail
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video")?.attr("poster")
                ?: document.selectFirst("img.preview")?.attr("src")

            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        // Eporner is single video per page, so one "episode"
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())

                // Try to get upload date
                val document = response.asJsoup()
                val dateText = document.selectFirst("time, span.date, div.uploaded")?.text()
                dateText?.let {
                    try {
                        // Try various date formats
                        val formats = listOf(
                            SimpleDateFormat("MMMM dd, yyyy", Locale.US),
                            SimpleDateFormat("MMM dd, yyyy", Locale.US),
                            SimpleDateFormat("yyyy-MM-dd", Locale.US),
                            SimpleDateFormat("dd/MM/yyyy", Locale.US),
                        )

                        for (format in formats) {
                            try {
                                date_upload = format.parse(it)?.time ?: 0L
                                if (date_upload > 0) break
                            } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                }
            },
        )
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Method 1: Check for direct video sources
        document.select("video source").forEach { source ->
            val url = source.attr("src")
            if (url.isNotBlank() && url.contains(".mp4")) {
                val quality = source.attr("label")?.takeIf { it.isNotBlank() }
                    ?: extractQualityFromUrl(url)
                videos.add(Video(url, "Direct: $quality", url))
            }
        }

        // Method 2: Check for iframes with video players
        document.select("iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src")
            if (iframeUrl.contains("eporner.com/embed/")) {
                // Extract video ID from embed URL
                val videoId = iframeUrl.substringAfter("/embed/").substringBefore("/")
                if (videoId.isNotBlank()) {
                    // Try to get direct video links from API or page
                    val directLinks = getDirectVideoLinks(videoId)
                    videos.addAll(directLinks)
                }
            }
        }

        // Method 3: Check for HLS/m3u8 playlists
        val scripts = document.select("script").toString()
        val hlsRegex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
        hlsRegex.findAll(scripts).forEach { match ->
            val hlsUrl = match.value
            if (hlsUrl.contains("eporner")) {
                try {
                    val hlsVideos = playlistUtils.extractFromHls(
                        hlsUrl,
                        response.request.url.toString(),
                        videoNameGen = { quality -> "HLS: $quality" },
                    )
                    videos.addAll(hlsVideos)
                } catch (e: Exception) {
                    // Silently fail
                }
            }
        }

        // Method 4: Look for download links
        document.select("a[href*=.mp4], a[href*=.webm], a[download]").forEach { link ->
            val url = link.attr("href")
            if (url.contains(".mp4") || url.contains(".webm")) {
                val quality = extractQualityFromUrl(url) ?: "Unknown"
                videos.add(Video(url, "Download: $quality", url))
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    private fun getDirectVideoLinks(videoId: String): List<Video> {
        // Try to fetch video page with different quality parameters
        val qualities = listOf("1080", "720", "480", "360", "240")
        val videos = mutableListOf<Video>()

        qualities.forEach { quality ->
            try {
                // Try common eporner video URL patterns
                val possibleUrls = listOf(
                    "$baseUrl/download/$videoId/$quality/",
                    "$baseUrl/video/$videoId/$quality/",
                    "https://cdn.eporner.com/video/$videoId/${quality}p.mp4",
                )

                for (url in possibleUrls) {
                    try {
                        val request = GET(url, headers)
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            videos.add(Video(url, "${quality}p", url))
                            response.close()
                            break
                        }
                        response.close()
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }

        return videos
    }

    private fun extractQualityFromUrl(url: String): String {
        val regex = Regex("""(\d{3,4})[pP]""")
        return regex.find(url)?.groupValues?.get(1) ?: "Unknown"
    }

    // ============================== Filters ==============================
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Category filter only works without text search"),
    )

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? =
        filterIsInstance<T>().firstOrNull()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ========================= Video Sorting =========================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p", "240p")
    }
}

// ============================== Filters ==============================
class CategoryFilter : AnimeFilter.Select<String>(
    "Category",
    CATEGORIES.map { it.first }.toTypedArray(),
) {
    val selected get() = CATEGORIES[state].second.takeUnless { state == 0 }

    companion object {
        // Common eporner categories - you might need to update these
        val CATEGORIES = listOf(
            Pair("<Select>", ""),
            Pair("Amateur", "amateur"),
            Pair("Anal", "anal"),
            Pair("Asian", "asian"),
            Pair("BBW", "bbw"),
            Pair("Big Tits", "big-tits"),
            Pair("Blonde", "blonde"),
            Pair("Blowjob", "blowjob"),
            Pair("Brunette", "brunette"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Facial", "facial"),
            Pair("Gangbang", "gangbang"),
            Pair("Hardcore", "hardcore"),
            Pair("Latina", "latina"),
            Pair("Lesbian", "lesbian"),
            Pair("MILF", "milf"),
            Pair("POV", "pov"),
            Pair("Teen", "teen"),
            Pair("Threesome", "threesome"),
        )
    }
}
