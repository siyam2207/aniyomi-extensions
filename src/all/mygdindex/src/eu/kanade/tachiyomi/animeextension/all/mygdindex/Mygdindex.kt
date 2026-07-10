package eu.kanade.tachiyomi.animeextension.all.mygdindex

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

/*
 * ============================================================================
 * IMPORTANT — READ BEFORE BUILDING
 * ============================================================================
 * This extension talks DIRECTLY to a GDI-JS (https://gdi.js.org) deployment's
 * real API, reverse-engineered from a copy of its worker.js — NOT the
 * "reverse the whole body / strip 24+20 chars / base64-decode" scheme the
 * original GoogleDriveIndex extension expects. GDI-JS doesn't work that way,
 * so none of that is used here.
 *
 * Confirmed directly from the worker.js source:
 *   - Folder listing: POST a JSON body {"page_token":..,"page_index":..} to
 *     "<domain>/<N>:/path/.../" -> plain JSON back:
 *       {"data":{"files":[...]}, "nextPageToken":..., "curPageIndex":...}
 *   - Each file's "id"/"driveId" ARE individually AES-encrypted server-side,
 *     but this extension never decrypts them — folders are still browsed by
 *     NAME (path-based), and files carry a ready-to-use, pre-signed "link"
 *     for playback that the server verifies later. crypto_base_key /
 *     hmac_base_key are never touched here.
 *   - Search: POST {"q":..,"page_token":..,"page_index":..} to
 *     "<domain>/<N>:search" -> same shape, plus a "rootIdx" per file.
 *   - id2path: POST {"id":"<opaque encrypted id from a search result>"} to
 *     "<domain>/<N>:id2path" -> {"path":"/0:/real/path/to/item"}. The id is
 *     passed through as an opaque token; never decrypted client-side.
 *
 * THIS FILE IS UNTESTED — written without an Android/Gradle toolchain to
 * compile or run it. Treat it as a strong first draft: build it, send back
 * whatever compiler errors or runtime crashes come up, and we'll fix them
 * the same way we debugged the worker.js. Likely rough edges:
 *   - `id` below was generated locally guessing Tachiyomi's MD5-based
 *     source-id scheme. Verify it doesn't collide with another installed
 *     source; regenerate if it does.
 *   - Multi-drive (multiple "roots") setups will need the drive-index ("N:")
 *     handling double-checked; this draft assumes the common single-root
 *     case but tries to stay correct for multi-root too.
 * ============================================================================
 */
class MyGdindex :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MyGdindex"

    // Generated locally from "MyGdindex"/"all" using Tachiyomi's MD5-based
    // source-id scheme — VERIFY this against your build (or regenerate) to
    // make sure it doesn't collide with another installed source's id.
    override val id = 3900226815696505660L

    override val baseUrl by lazy {
        preferences.domainList.split(",").first().removeName()
    }

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private var pageToken: String? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ============================== Popular ================================

    override fun popularAnimeRequest(page: Int): Request {
        require(baseUrl.isNotEmpty()) { "Enter drive path(s) in extension settings." }
        return listRequest(baseUrl, page)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response, baseUrl)

    // =============================== Latest =================================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ==================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        if (urlFilter.state.isNotEmpty()) {
            return addSinglePage(urlFilter.state)
        }

        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = if (serverFilter.vals.isEmpty()) baseUrl else serverFilter.toUriPart()

        if (query.isBlank()) {
            val req = listRequest(serverUrl, page)
            return client.newCall(req).awaitSuccess().let { parseListing(it, serverUrl) }
        }

        if (page == 1) pageToken = null
        val root = serverUrl.toHttpUrl()
        val searchUrl = "${root.scheme}://${root.host}/${root.pathSegments.first()}search"
        val bodyStr = json.encodeToString(SearchRequestBody(query, pageToken, page - 1))
        val req = POST(
            searchUrl,
            body = bodyStr.toRequestBody(jsonMediaType),
            headers = jsonHeaders(searchUrl),
        )
        return client.newCall(req).awaitSuccess().let { parseSearch(it, serverUrl) }
    }

    // ============================== Filters ==================================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search will only search inside selected server"),
        ServerFilter(getDomains()),
        AnimeFilter.Header("Add single folder"),
        URLFilter(),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) :
        UriPartFilter("Select server", domains)

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.domainList.isBlank()) return emptyArray()
        return preferences.domainList.split(",").map {
            val match = URL_REGEX.matchEntire(it)!!
            val name = match.groups["name"]?.let { g -> g.value.substringAfter("[").substringBeforeLast("]") }
            Pair(name ?: it.toHttpUrl().encodedPath, it.removeName())
        }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    private fun addSinglePage(inputUrl: String): AnimesPage {
        val match = URL_REGEX.matchEntire(inputUrl) ?: throw Exception("Invalid url")
        val anime = SAnime.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]") ?: "Folder"
            url = LinkData(
                type = "multi",
                url = match.groups["url"]!!.value,
                fragment = inputUrl.removeName().toHttpUrl().encodedFragment,
            ).toJsonString()
            thumbnail_url = ""
        }
        return AnimesPage(listOf(anime), false)
    }

    // ============================ Anime Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        // Nothing extra to fetch — everything needed is already in the URL/name.
        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)
        var counter = 1
        val maxRecursionDepth = parsed.fragment?.substringBefore(",")?.toIntOrNull() ?: 2
        val (start, stop) = if (parsed.fragment?.contains(",") == true) {
            parsed.fragment.substringAfter(",").split(",").map { it.toInt() }
        } else {
            listOf(null, null)
        }

        val resolved = resolveIfSearch(parsed)

        if (resolved.type == "single") {
            val titleName = resolved.url.toHttpUrl().pathSegments.lastOrNull()?.let { decodeSeg(it) }
                ?: resolved.url.toHttpUrl().pathSegments.last()
            episodeList.add(
                SEpisode.create().apply {
                    name = if (preferences.trimEpisodeName) titleName.trimInfo() else titleName
                    url = resolved.url
                    episode_number = 1F
                    date_upload = -1L
                    scanlator = resolved.info
                },
            )
            return episodeList
        }

        val basePathCounter = resolved.url.toHttpUrl().pathSize

        suspend fun traverseDirectory(folderUrl: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return
            var token: String? = null
            var index = 0

            while (true) {
                val resp = client.newCall(listRequest(folderUrl, index + 1, token)).awaitSuccess()
                val parsedResp = json.decodeFromString<GDIListResponse>(resp.body.string())

                for (item in parsedResp.data.files) {
                    if (item.mimeType.endsWith("folder")) {
                        traverseDirectory(joinUrl(folderUrl, item.name).addSuffix("/"), recursionDepth + 1)
                        continue
                    }
                    if (!item.mimeType.startsWith("video/")) continue

                    if (start != null && maxRecursionDepth == 1 && counter < start) {
                        counter++
                        continue
                    }
                    if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                    val epUrl = item.link ?: joinUrl(folderUrl, item.name)
                    val paths = folderUrl.toHttpUrl().pathSegments
                    val extraInfo = if (paths.size > basePathCounter) {
                        "/" + paths.subList(basePathCounter - 1, paths.size).joinToString("/") { it.trimInfo() }
                    } else {
                        "/"
                    }
                    val size = item.size?.toLongOrNull()?.let { formatFileSize(it) }

                    episodeList.add(
                        SEpisode.create().apply {
                            name = if (preferences.trimEpisodeName) item.name.trimInfo() else item.name
                            this.url = epUrl
                            scanlator = "${size ?: ""} • $extraInfo"
                            date_upload = -1L
                            episode_number = counter.toFloat()
                        },
                    )
                    counter++
                }

                token = parsedResp.nextPageToken
                index += 1
                if (token == null) break
            }
        }

        traverseDirectory(resolved.url)

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links ==================================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // episode.url is already either a pre-signed "link" (from a listing/search
        // response) or a plain path URL — both are directly playable, no further
        // resolution needed.
        return listOf(Video(episode.url, "Video", episode.url))
    }

    // ============================= Utilities ====================================

    private fun listRequest(url: String, page: Int, tokenOverride: String? = null): Request {
        if (page == 1) pageToken = null
        val body = json.encodeToString(ListRequestBody(tokenOverride ?: pageToken, page - 1))
        return POST(url, body = body.toRequestBody(jsonMediaType), headers = jsonHeaders(url))
    }

    private fun jsonHeaders(url: String) = headers.newBuilder()
        .add("Accept", "*/*")
        .add("Content-Type", "application/json;charset=UTF-8")
        .add("Host", url.toHttpUrl().host)
        .add("Origin", "https://${url.toHttpUrl().host}")
        .add("Referer", url)
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun parseListing(response: Response, url: String): AnimesPage {
        val parsed = json.decodeFromString<GDIListResponse>(response.body.string())
        val animeList = parsed.data.files.map { file ->
            SAnime.create().apply {
                title = if (preferences.trimAnimeName) file.name.trimInfo() else file.name
                thumbnail_url = ""
                this.url = if (file.mimeType.endsWith("folder")) {
                    LinkData(
                        "multi",
                        joinUrl(url, file.name).addSuffix("/"),
                        fragment = url.toHttpUrl().encodedFragment,
                    ).toJsonString()
                } else {
                    LinkData(
                        "single",
                        file.link ?: joinUrl(url, file.name),
                        file.size?.toLongOrNull()?.let { formatFileSize(it) },
                    ).toJsonString()
                }
            }
        }
        pageToken = parsed.nextPageToken
        return AnimesPage(animeList, parsed.nextPageToken != null)
    }

    private fun parseSearch(response: Response, serverUrl: String): AnimesPage {
        val parsed = json.decodeFromString<GDIListResponse>(response.body.string())
        val root = serverUrl.toHttpUrl()
        val host = "${root.scheme}://${root.host}"
        val order = ORDER_REGEX.find(root.encodedPath)?.groupValues?.get(1) ?: "0"

        val animeList = parsed.data.files.map { file ->
            SAnime.create().apply {
                title = if (preferences.trimAnimeName) file.name.trimInfo() else file.name
                thumbnail_url = ""
                this.url = if (file.mimeType.endsWith("folder")) {
                    // Folders from search only carry an opaque encrypted id — resolve
                    // via id2path (server-side only; never decrypted client-side)
                    // the first time this entry is opened.
                    LinkData("search", file.id, idHost = host, idOrder = order.toIntOrNull() ?: 0).toJsonString()
                } else {
                    LinkData(
                        "single",
                        file.link ?: file.id,
                        file.size?.toLongOrNull()?.let { formatFileSize(it) },
                    ).toJsonString()
                }
            }
        }
        pageToken = parsed.nextPageToken
        return AnimesPage(animeList, parsed.nextPageToken != null)
    }

    // Resolves a "search" LinkData (opaque encrypted folder id) into a real
    // browsable "multi" path by calling id2path. No-op for any other type.
    private suspend fun resolveIfSearch(data: LinkData): LinkData {
        if (data.type != "search") return data
        val host = data.idHost ?: throw Exception("Missing host for id2path resolution")
        val order = data.idOrder ?: 0
        val id2pathUrl = "$host/$order:id2path"
        val body = json.encodeToString(Id2PathRequestBody(data.url)) // data.url holds the opaque encrypted id here
        val req = POST(id2pathUrl, body = body.toRequestBody(jsonMediaType), headers = jsonHeaders(id2pathUrl))
        val resp = client.newCall(req).awaitSuccess()
        val parsed = json.decodeFromString<IdToPathResponse>(resp.body.string())
        val path = parsed.path ?: throw Exception("Could not resolve this item's location (id2path returned no path)")
        return LinkData("multi", "$host$path".let { if (it.endsWith("/")) it else "$it/" })
    }

    private fun decodeSeg(seg: String): String = try {
        java.net.URLDecoder.decode(seg, "UTF-8")
    } catch (e: Exception) {
        seg
    }

    private fun joinUrl(path1: String, path2: String): String = path1.removeSuffix("/") + "/" + path2.removePrefix("/")

    private fun String.addSuffix(suffix: String): String = if (this.endsWith(suffix)) this else this.plus(suffix)

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[[\w-]+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()
        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult -> matchResult.groups[2]?.value ?: "" }
        }
        return newString.trim()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun String.removeName(): String = Regex("""^(\[[^\[\];]+\])""").replace(this, "")

    private fun LinkData.toJsonString(): String = json.encodeToString(this)

    private fun isUrl(text: String) = URL_REGEX matches text

    /*
     * Stolen from the MangaDex manga extension (same pattern as the original
     * GoogleDriveIndex extension's validator).
     */
    private fun setupEditTextUrlValidator(editText: EditText) {
        editText.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(editable: Editable?) {
                    requireNotNull(editable)
                    val text = editable.toString()
                    val isValid = text.isBlank() || text.split(",").map(String::trim).all(::isUrl)
                    editText.error = if (!isValid) "${text.split(",").first { !isUrl(it) }} is not a valid url" else null
                    editText.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled = editText.error == null
                }
            },
        )
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "domain_list"
        private const val DOMAIN_PREF_DEFAULT = ""

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode_name"
        private const val TRIM_EPISODE_NAME_DEFAULT = true

        private const val TRIM_ANIME_NAME_KEY = "trim_anime_name"
        private const val TRIM_ANIME_NAME_DEFAULT = true

        private val URL_REGEX = Regex("""(?<name>\[[^\[\];]+\])?(?<url>https(?:[^,#]+))(?<depth>#\d+(?<range>,\d+,\d+)?)?$""")
        private val ORDER_REGEX = Regex("""^/(\d+):""")
    }

    private val SharedPreferences.domainList
        get() = getString(DOMAIN_PREF_KEY, DOMAIN_PREF_DEFAULT)!!

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    private val SharedPreferences.trimAnimeName
        get() = getBoolean(TRIM_ANIME_NAME_KEY, TRIM_ANIME_NAME_DEFAULT)

    // ============================== Settings ====================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Enter GDI-JS site paths to be shown in extension"
            summary = """Enter drive index paths to be shown in extension
                |Enter as comma separated list
            """.trimMargin()
            this.setDefaultValue(DOMAIN_PREF_DEFAULT)
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a comma.
                |- Format: https://your-site.workers.dev/0:/
                |- (optional) Add [] before url to customize name. For example: [My Drive]https://your-site.workers.dev/0:/
                |- (optional) add #<integer> to limit the depth of recursion when loading episodes, default is 2.
                |- (optional) add #depth,start,stop (all integers) to specify a range. Only works if depth is 1.
            """.trimMargin()

            setOnBindEditTextListener(::setupEditTextUrlValidator)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(DOMAIN_PREF_KEY, newValue as String).commit()
                    Toast.makeText(screen.context, "Restart App to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)
    }
}
