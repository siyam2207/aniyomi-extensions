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
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        // Eporner uses: https://www.eporner.com/hd-porn/ (no page number for first page)
        // Page 2: https://www.eporner.com/hd-porn/2/
        val url = if (page == 1) {
            "$baseUrl/hd-porn/"
        } else {
            "$baseUrl/hd-porn/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Correct selector for Eporner: videos are in div#videoas
        val elements = document.select("#videoas .mb, .videobox")

        val animeList = elements.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank() || !href.startsWith("/video-")) return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = link.selectFirst("img")?.attr("alt")
                    ?: element.selectFirst(".title")?.text()
                    ?: "Unknown Title"
                thumbnail_url = link.selectFirst("img")?.attr("src")
                    ?: link.selectFirst("img")?.attr("data-src")
                    ?: element.selectFirst("img")?.attr("src")
            }
        }

        // Check for next page
        val hasNextPage = document.select(".pagination a").any {
            it.text().contains("Next", ignoreCase = true) ||
                it.text() == "»" ||
                it.text().toIntOrNull() == page + 1
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        // Eporner latest: https://www.eporner.com/latest/ (no page for first)
        val url = if (page == 1) {
            "$baseUrl/latest/"
        } else {
            "$baseUrl/latest/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val pageNum = page
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected

        return if (query.isNotBlank()) {
            // Eporner search: https://www.eporner.com/search/query/ (no page for first)
            val url = if (page == 1) {
                "$baseUrl/search/${query.encodeUtf8()}/"
            } else {
                "$baseUrl/search/${query.encodeUtf8()}/$page/"
            }
            GET(url, headers)
        } else if (category != null) {
            // Eporner category: https://www.eporner.com/category/cat-name/
            val url = if (page == 1) {
                "$baseUrl/category/$category/"
            } else {
                "$baseUrl/category/$category/$page/"
            }
            GET(url, headers)
        } else {
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
                ?: "Unknown Title"

            // Video info
            val infoSection = document.selectFirst(".info-container")

            // Categories/tags
            genre = document.select("a[href^=/category/]")
                .joinToString { it.text() }

            // Pornstars
            artist = document.select("a[href^=/model/]")
                .joinToString { it.text() }

            // Description from meta tag
            description = document.selectFirst("meta[name=description]")?.attr("content")
                ?: "No description available"

            // Thumbnail
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".preview")?.attr("src")
                ?: document.selectFirst("#player")?.attr("poster")

            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                setUrlWithoutDomain(response.request.url.toString())

                // Get upload date from page
                val document = response.asJsoup()
                val dateText = document.selectFirst(".info .added")?.text()
                    ?.substringAfter("Added: ")

                dateText?.let {
                    try {
                        // Try to parse date like "2 days ago", "3 weeks ago", or actual date
                        if (it.contains("day") || it.contains("week") || it.contains("month") || it.contains("year")) {
                            // Relative time
                            date_upload = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                        } else {
                            // Try actual date parsing
                            val format = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
                            date_upload = format.parse(it)?.time ?: 0L
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

        // Eporner uses a video player with data-cfsrc attributes
        document.select("video source").forEach { source ->
            val url = source.attr("src").takeIf { it.isNotBlank() }
                ?: source.attr("data-cfsrc").takeIf { it.isNotBlank() }

            if (url != null && (url.contains(".mp4") || url.contains(".webm"))) {
                val quality = source.attr("title") ?: extractQualityFromUrl(url)
                videos.add(Video(url, "Direct: $quality", url))
            }
        }

        // Check for download links
        document.select("a.download[href*=.mp4]").forEach { link ->
            val url = link.attr("href")
            if (url.isNotBlank()) {
                val quality = link.text().takeIf { it.contains("p") }
                    ?: extractQualityFromUrl(url)
                    ?: "Download"
                videos.add(Video(url, quality, url))
            }
        }

        // Look for video URLs in scripts
        val scriptText = document.select("script").toString()

        // Pattern for Eporner's video URLs
        val videoRegex = Regex("""["']?(https?://[^"']+?\.mp4(?:\?[^"']*)?)["']?""")
        videoRegex.findAll(scriptText).forEach { match ->
            val url = match.groupValues[1]
            if (url.contains("eporner") && !videos.any { it.videoUrl == url }) {
                val quality = extractQualityFromUrl(url)
                videos.add(Video(url, "Script: $quality", url))
            }
        }

        return videos.distinctBy { it.videoUrl }.takeIf { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun extractQualityFromUrl(url: String): String {
        val regex = Regex("""/(\d{3,4})[pP]?\.mp4""")
        return regex.find(url)?.groupValues?.get(1)?.let { "${it}p" } ?: "Unknown"
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
        // Eporner actual categories from their menu
        val CATEGORIES = listOf(
            Pair("<Select>", ""),
            Pair("HD Porn", "hd-porn"),
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
