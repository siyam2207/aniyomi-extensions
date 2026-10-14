package eu.kanade.tachiyomi.animeextension.all.telegrambridge

import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/*
 * Streams videos from a private Telegram channel through a small bridge server
 * (bridge.py, running in Termux on the phone or on a VPS).
 *
 * Bridge contract:
 *   GET  <bridge>/list?limit=N&offset_id=ID&q=TEXT   (header X-Token)
 *        -> {"items":[{id,title,filename,size,mime,duration,date}], "next_offset_id": ID|null}
 *   GET  <bridge>/stream/<message id>                (header X-Token, supports Range)
 *
 * Two browse modes (set in the extension settings):
 *   - "Each video is an entry": every video in the channel is its own item.
 *   - "Whole channel as one series": a single item whose episodes are all the videos.
 *
 * NOTE: written without an Android/Gradle toolchain, so it has not been compiled yet.
 * Build it and report any compiler error or crash.
 */
class TelegramBridge :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Telegram Bridge"

    // Read live, so changing the URL in settings applies without restarting the app.
    override val baseUrl: String
        get() = preferences.bridgeUrl

    override val lang = "all"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    // Seeking into a large file makes the bridge fetch from Telegram first, so be patient.
    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var popularOffset: Long = 0L
    private var searchOffset: Long = 0L

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (preferences.browseMode == MODE_CHANNEL) {
            val series = SAnime.create().apply {
                title = preferences.seriesTitle
                url = URL_CHANNEL
                status = SAnime.COMPLETED
                description = "Every video in the Telegram channel, as episodes."
            }
            return AnimesPage(listOf(series), false)
        }

        if (page == 1) popularOffset = 0L
        val result = fetchList(offsetId = popularOffset, query = null)
        popularOffset = result.nextOffsetId ?: 0L
        return AnimesPage(result.items.map(::toAnime), result.nextOffsetId != null)
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    // Search matches the captions of the videos in the channel.
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (page == 1) searchOffset = 0L
        val result = fetchList(offsetId = searchOffset, query = query.ifBlank { null })
        searchOffset = result.nextOffsetId ?: 0L
        return AnimesPage(result.items.map(::toAnime), result.nextOffsetId != null)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================ Anime Details ===========================

    // Everything is already filled in from the list, nothing more to fetch.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url != URL_CHANNEL) {
            // A single video: the anime url is "v:<message id>".
            val id = anime.url.removePrefix(URL_VIDEO_PREFIX)
            return listOf(
                SEpisode.create().apply {
                    url = id
                    name = anime.title
                    episode_number = 1F
                },
            )
        }

        // Whole channel: page through everything (the bridge returns newest first).
        val all = mutableListOf<BridgeVideo>()
        var offset = 0L
        for (i in 0 until MAX_PAGES) {
            val result = fetchList(offsetId = offset, query = null, limit = EPISODE_PAGE_SIZE)
            all += result.items
            offset = result.nextOffsetId ?: break
        }
        return buildEpisodes(all)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // Newest first, numbered oldest -> newest (1 is the oldest video in the channel).
    private fun buildEpisodes(videos: List<BridgeVideo>): List<SEpisode> {
        val total = videos.size
        return videos.mapIndexed { index, video ->
            SEpisode.create().apply {
                url = video.id.toString()
                name = video.displayTitle()
                episode_number = (total - index).toFloat()
                date_upload = parseDate(video.date)
                scanlator = formatSize(video.size)
            }
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val streamUrl = "${baseUrl.trimEnd('/')}/stream/${episode.url}"
        return listOf(Video(streamUrl, "Telegram", streamUrl, headers = authHeaders()))
    }

    // ============================= Utilities ==============================

    private fun authHeaders(): Headers = headers.newBuilder()
        .set("X-Token", preferences.token)
        .build()

    private suspend fun fetchList(
        offsetId: Long,
        query: String?,
        limit: Int = LIST_PAGE_SIZE,
    ): BridgeListResponse {
        require(baseUrl.isNotBlank()) { "Set the bridge URL in the extension settings." }
        require(preferences.token.isNotBlank()) { "Set the bridge token in the extension settings." }

        val url = "${baseUrl.trimEnd('/')}/list".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset_id", offsetId.toString())
            .apply { if (query != null) addQueryParameter("q", query) }
            .build()

        return client.newCall(GET(url, authHeaders())).awaitSuccess().parseAs<BridgeListResponse>()
    }

    private fun toAnime(video: BridgeVideo): SAnime = SAnime.create().apply {
        title = video.displayTitle()
        url = URL_VIDEO_PREFIX + video.id
        status = SAnime.COMPLETED
        description = listOfNotNull(
            formatSize(video.size).ifEmpty { null },
            formatDuration(video.duration),
            video.date?.take(10),
        ).joinToString(" • ")
    }

    private fun BridgeVideo.displayTitle(): String = title.ifBlank { filename }.ifBlank { "Video ${this.id}" }

    // The bridge sends UTC timestamps like 2026-10-13T11:57:56+00:00; only the day is used.
    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value.take(10))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
        bytes > 0 -> "%.1f KB".format(Locale.US, bytes / 1_000.0)
        else -> ""
    }

    private fun formatDuration(seconds: Double): String? {
        val total = seconds.toLong()
        if (total <= 0) return null
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ============================== Settings ==============================

    private val SharedPreferences.bridgeUrl: String
        get() = getString(BRIDGE_URL_KEY, BRIDGE_URL_DEFAULT)!!.trim()

    private val SharedPreferences.token: String
        get() = getString(TOKEN_KEY, TOKEN_DEFAULT)!!.trim()

    private val SharedPreferences.browseMode: String
        get() = getString(BROWSE_MODE_KEY, MODE_VIDEOS)!!

    private val SharedPreferences.seriesTitle: String
        get() = getString(SERIES_TITLE_KEY, SERIES_TITLE_DEFAULT)!!.ifBlank { SERIES_TITLE_DEFAULT }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BRIDGE_URL_KEY
            title = "Bridge URL"
            summary = "Address of your bridge server, e.g. http://127.0.0.1:8080"
            setDefaultValue(BRIDGE_URL_DEFAULT)
            dialogTitle = "Bridge URL"
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                if (value.isNotEmpty() && value.toHttpUrlOrNull() == null) {
                    Toast.makeText(screen.context, "That is not a valid URL", Toast.LENGTH_LONG).show()
                    false
                } else {
                    preferences.edit().putString(key, value).commit()
                }
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = TOKEN_KEY
            title = "Bridge token"
            summary = "The TOKEN you set when starting the bridge (hidden)"
            setDefaultValue(TOKEN_DEFAULT)
            dialogTitle = "Bridge token"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, (newValue as String).trim()).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = BROWSE_MODE_KEY
            title = "Browse mode"
            summary = "%s"
            entries = arrayOf("Each video is an entry", "Whole channel as one series")
            entryValues = arrayOf(MODE_VIDEOS, MODE_CHANNEL)
            setDefaultValue(MODE_VIDEOS)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = SERIES_TITLE_KEY
            title = "Series title"
            summary = "Name shown in \"Whole channel as one series\" mode"
            setDefaultValue(SERIES_TITLE_DEFAULT)
            dialogTitle = "Series title"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val BRIDGE_URL_KEY = "bridge_url"
        private const val BRIDGE_URL_DEFAULT = "http://127.0.0.1:8080"

        private const val TOKEN_KEY = "bridge_token"
        private const val TOKEN_DEFAULT = ""

        private const val BROWSE_MODE_KEY = "browse_mode"
        private const val MODE_VIDEOS = "videos"
        private const val MODE_CHANNEL = "channel"

        private const val SERIES_TITLE_KEY = "series_title"
        private const val SERIES_TITLE_DEFAULT = "Telegram Channel"

        private const val URL_CHANNEL = "channel"
        private const val URL_VIDEO_PREFIX = "v:"

        private const val LIST_PAGE_SIZE = 40
        private const val EPISODE_PAGE_SIZE = 500
        private const val MAX_PAGES = 20
    }
}
