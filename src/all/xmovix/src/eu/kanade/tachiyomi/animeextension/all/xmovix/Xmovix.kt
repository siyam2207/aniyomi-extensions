package eu.kanade.tachiyomi.animeextension.all.xmovix

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Xmovix : AnimeHttpSource(), ConfigurableAnimeSource {
    // ====================
    // Source Metadata
    // ====================
    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net/en"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // ====================
    // Preferences
    // ====================
    private val preferences by getPreferencesLazy()

    // ====================
    // Headers
    // ====================
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Accept-Encoding", "gzip, deflate, br")
            .add("Connection", "keep-alive")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Cache-Control", "max-age=0")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
    }

    // ====================
    // Extractor Instance
    // ====================
    private val doodExtractor by lazy { DoodExtractor(client) }

    // ====================
    // Popular Anime
    // ====================
    override fun popularAnimeRequest(page: Int): Request {
        // The site uses /most-viewed/ for popular content
        return GET("$baseUrl/most-viewed/?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document.select("div.item").mapNotNull { element ->
            runCatching {
                SAnime.create().apply {
                    val link = element.selectFirst("a")!!
                    title = link.attr("title").takeIf { it.isNotBlank() }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: "Untitled"
                    setUrlWithoutDomain(link.attr("href"))
                    thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                }
            }.getOrNull()
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ====================
    // Latest Updates
    // ====================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response) // Same structure as popular
    }

    // ====================
    // Search
    // ====================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            // Use the search endpoint discovered
            val formBody = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("story", query)
                .add("search_start", page.toString())
                .build()

            POST("$baseUrl/index.php", headers, formBody)
        } else {
            // Fallback to popular if no query
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Search results have different structure
        val animeList = document.select("div.search-item, div.item").mapNotNull { element ->
            runCatching {
                SAnime.create().apply {
                    val link = element.selectFirst("a")!!
                    title = link.attr("title").takeIf { it.isNotBlank() }
                        ?: element.selectFirst("h2, h3")?.text()
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: "Untitled"
                    setUrlWithoutDomain(link.attr("href"))
                    thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                }
            }.getOrNull()
        }

        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ====================
    // Anime Details
    // ====================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""

            // Try multiple possible selectors for description
            description = document.selectFirst("div.description, div.movie-desc, p.desc")?.text()

            // Genres/tags
            genre = document.select("a[href*='/genre/'], a[href*='/tag/'], div.tags a").eachText().joinToString()

            // Thumbnail - try multiple possible locations
            thumbnail_url = document.selectFirst("img.cover, img.poster, div.poster img")?.attr("abs:src")

            status = SAnime.COMPLETED // Adult films are typically completed

            // Additional metadata if available
            author = document.selectFirst("a[href*='/studio/']")?.text()
        }
    }

    // ====================
    // Episodes (Players)
    // ====================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val document = response.asJsoup()

        // Find all player links - adjust selector based on actual site structure
        return document.select("a.player-item, a[href*='player'], button[data-player]").mapIndexed { index, element ->
            SEpisode.create().apply {
                name = element.text().takeIf { it.isNotBlank() } ?: "Player ${index + 1}"
                url = element.attr("href").takeIf { it.isNotBlank() }
                    ?: element.attr("data-player")
                    ?: element.attr("onclick").substringAfter("'").substringBefore("'")
                episode_number = (index + 1).toFloat()
            }
        }
    }

    // Not used but required by interface
    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used")
    }

    // ====================
    // Video Extraction (Player 2 - DoodStream Only)
    // ====================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Find Player 2 link (DoodStream)
        // Adjust selector based on actual site structure
        val playerLinks = document.select("a.player-item, a[href*='player']")

        // Get the second player (index 1) as requested
        if (playerLinks.size >= 2) {
            val player2Link = playerLinks[1].attr("href").takeIf { it.isNotBlank() }

            if (player2Link != null) {
                try {
                    // Use the DoodExtractor you provided
                    val doodVideos = doodExtractor.videosFromUrl(player2Link, quality = "DoodStream")
                    videoList.addAll(doodVideos)
                } catch (e: Exception) {
                    // Log error but don't crash
                    Log.e("Xmovix", "Failed to extract DoodStream video: ${e.message}")
                }
            }
        }

        return videoList
    }

    // ====================
    // Video Sorting
    // ====================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        // Fix for contains() ambiguity - specify we're checking for a substring
        return sortedWith(
            compareByDescending { video -> video.quality.contains(quality, ignoreCase = false) },
        )
    }

    // ====================
    // Preferences
    // ====================
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

    // ====================
    // Filters (Not implemented)
    // ====================
    override fun getFilterList() = AnimeFilterList()

    // ====================
    // Companion Object (Constants)
    // ====================
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")
    }
}
