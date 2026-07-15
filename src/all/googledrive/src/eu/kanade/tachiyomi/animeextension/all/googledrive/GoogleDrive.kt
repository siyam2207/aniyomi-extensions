package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.commonEmptyRequestBody
import extensions.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ProtocolException
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.security.MessageDigest

class GoogleDrive : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = "https://drive.google.com"

    // Hack to manipulate what gets opened in webview
    private val baseUrlInternal by lazy {
        preferences.domainList.split(";").firstOrNull()
    }

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // Overriding headersBuilder() seems to cause issues with webview
    private val getHeaders = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", getCookie("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    private var nextPageToken: String? = ""

    // Per-folder pagination state used when merging multiple drive paths
    // together on the Popular tab (see getPopularAnime below).
    private var multiNextPageTokens: MutableMap<String, String?> = mutableMapOf()
    private var multiFailureCounts: MutableMap<String, Int> = mutableMapOf()

    // Same idea, but tracked separately for the Search tab so browsing
    // Popular and searching don't stomp on each other's pagination state.
    private var multiSearchTokens: MutableMap<String, String?> = mutableMapOf()
    private var multiSearchFailures: MutableMap<String, Int> = mutableMapOf()

    // Same idea, for the Latest tab.
    private var multiLatestTokens: MutableMap<String, String?> = mutableMapOf()
    private var multiLatestFailures: MutableMap<String, Int> = mutableMapOf()

    // ============================== Popular ===============================
    // Fans out to every configured folder and merges (interleaves) the
    // results instead of only using the first path in the preference list.

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val paths = getAllFolderEntries()
        require(paths.isNotEmpty()) { "Enter drive path(s) in extension settings." }

        // Keep the "open in WebView" button pointed at a valid folder.
        DRIVE_FOLDER_REGEX.matchEntire(paths.first())?.let {
            baseUrl = "https://drive.google.com/drive/folders/${it.groups["id"]!!.value}"
        }

        return mergeFolderResults(page, paths, multiNextPageTokens, multiFailureCounts) { path, token ->
            fetchFolderPage(path, token)
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val match = DRIVE_FOLDER_REGEX.matchEntire(baseUrlInternal!!)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    // Drive doesn't expose an upload/modified date we can see (no such field
    // showed up anywhere in this file), so "Latest" means "most recently
    // discovered by this extension" rather than a true upload timestamp —
    // see recordDiscovery/loadDiscoveryTimes below. First run after enabling
    // this will look unsorted until at least one browse has been recorded.

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val paths = getAllFolderEntries()
        require(paths.isNotEmpty()) { "Enter drive path(s) in extension settings." }

        val result = mergeFolderResults(page, paths, multiLatestTokens, multiLatestFailures) { path, token ->
            fetchFolderPage(path, token)
        }

        val discovery = loadDiscoveryTimes()
        val withTimestamps = result.animes.map { anime ->
            anime to (driveIdOf(anime)?.let { discovery[it] } ?: 0L)
        }
        val sorted = withTimestamps
            .sortedWith(compareByDescending<Pair<SAnime, Long>> { it.second }.thenBy { it.first.title.lowercase() })
            .map { (anime, discoveredAt) -> anime.markedIfNew(discoveredAt) }

        return AnimesPage(sorted, result.hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        if (urlFilter.state.isNotEmpty()) return addSinglePage(urlFilter.state)

        val allPaths = getAllFolderEntries()
        require(allPaths.isNotEmpty()) { "Enter drive path(s) in extension settings." }

        // "None checked" means "search everything," which is what makes this
        // merged/interleaved by default; checking specific folders narrows it.
        val folderGroup = filterList.find { it is FolderFilterGroup } as? FolderFilterGroup
        val checkedPaths = folderGroup?.state?.filter { it.state }?.map { it.path }.orEmpty()
        val paths = checkedPaths.ifEmpty { allPaths }

        val fetch: suspend (String, String) -> Pair<List<SAnime>, String?> = if (query.isEmpty()) {
            { path, token -> fetchFolderPage(path, token) }
        } else {
            { path, token -> fetchFolderSearchPage(path, query, token) }
        }

        return mergeFolderResults(page, paths, multiSearchTokens, multiSearchFailures, fetch)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        val match = DRIVE_FOLDER_REGEX.matchEntire(serverUrl)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList {
        val domains = getDomains()
        return AnimeFilterList(
            ServerFilter(domains),
            AnimeFilter.Separator(),
            FolderFilterGroup(domains),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Add single folder"),
            URLFilter(),
        )
    }

    private class ServerFilter(domains: Array<Pair<String, String>>) : UriPartFilter(
        "Select drive path",
        domains,
    )

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.domainList.isBlank()) return emptyArray()
        return preferences.domainList.split(";").map { Pair(labelForPath(it), it) }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    private class FolderCheckBox(name: String, val path: String) : AnimeFilter.CheckBox(name, false)

    // Leaving every box unchecked (the default) searches every configured
    // folder, which is what makes results "interleaved" instead of picking
    // just one. Checking specific boxes narrows the merge down to those.
    private class FolderFilterGroup(folders: Array<Pair<String, String>>) :
        AnimeFilter.Group<FolderCheckBox>(
            "Limit to folders (none checked = search all)",
            folders.map { (label, path) -> FolderCheckBox(label, path) },
        )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = json.decodeFromString<LinkData>(anime.url)
        return GET(parsed.url, headers = getHeaders)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = json.decodeFromString<LinkData>(anime.url)

        if (parsed.type == "single") return anime

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            null
        } ?: return anime

        // Get cover

        val coverResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        coverResponse.items?.firstOrNull()?.let {
            anime.thumbnail_url = "https://drive.google.com/uc?id=${it.id}"
        }

        // Get details

        val detailsResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "details.json", "")),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        detailsResponse.items?.firstOrNull()?.let {
            val newPostHeaders = getHeaders.newBuilder().apply {
                add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                set("Host", "drive.usercontent.google.com")
                add("Origin", "https://drive.google.com")
                add("Referer", "https://drive.google.com/")
                add("X-Drive-First-Party", "DriveWebUi")
                add("X-Json-Requested", "true")
            }.build()

            val newPostUrl = "https://drive.usercontent.google.com/uc?id=${it.id}&authuser=0&export=download"

            val newResponse = client.newCall(
                POST(newPostUrl, headers = newPostHeaders, body = commonEmptyRequestBody),
            ).execute().parseAs<DownloadResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

            val downloadHeaders = headers.newBuilder().apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                add("Connection", "keep-alive")
                add("Cookie", getCookie("https://drive.usercontent.google.com"))
                add("Host", "drive.usercontent.google.com")
            }.build()

            client.newCall(
                GET(newResponse.downloadUrl, headers = downloadHeaders),
            ).execute().parseAs<DetailsJson>().let { t ->
                t.title?.let { anime.title = it }
                t.author?.let { anime.author = it }
                t.artist?.let { anime.artist = it }
                t.description?.let { anime.description = it }
                t.genre?.let { anime.genre = it.joinToString(", ") }
                t.status?.let { anime.status = it.toIntOrNull() ?: SAnime.UNKNOWN }
            }
        }

        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)

        if (parsed.type == "single") {
            return listOf(
                SEpisode.create().apply {
                    name = "Video"
                    scanlator = parsed.info!!.size
                    url = parsed.url
                    episode_number = 1F
                    date_upload = -1L
                },
            )
        }

        val match = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!! // .groups["id"]!!.value
        val maxRecursionDepth = match.groups["depth"]?.let {
            it.value.substringAfter("#").substringBefore(",").toInt()
        } ?: 2
        val (start, stop) = match.groups["range"]?.let {
            it.value.substringAfter(",").split(",").map { it.toInt() }
        } ?: listOf(null, null)

        fun traverseFolder(folderUrl: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = DRIVE_FOLDER_REGEX.matchEntire(folderUrl)!!.groups["id"]!!.value

            val driveDocument = try {
                client.newCall(GET(folderUrl, headers = getHeaders)).execute().asJsoup()
            } catch (a: ProtocolException) {
                throw Exception("Unable to get items, check webview")
            }

            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            var pageToken: String? = ""
            var counter = 1

            while (pageToken != null) {
                val response = client.newCall(
                    createPost(driveDocument, folderId, pageToken),
                ).execute()

                val parsed = response.parseAs<PostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val size = it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: ""
                        val pathName = if (preferences.trimEpisodeInfo) path.trimInfo() else path

                        if (start != null && maxRecursionDepth == 1 && counter < start) {
                            counter++
                            return@forEachIndexed
                        }
                        if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                        episodeList.add(
                            SEpisode.create().apply {
                                name =
                                    if (preferences.trimEpisodeName) it.title.trimInfo() else it.title
                                url = "https://drive.google.com/uc?id=${it.id}"
                                episode_number =
                                    ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)
                                        ?.toFloatOrNull() ?: (index + 1).toFloat()
                                date_upload = -1L
                                scanlator = if (preferences.scanlatorOrder) {
                                    "/$pathName • $size"
                                } else {
                                    "$size • /$pathName"
                                }
                            },
                        )
                        counter++
                    }
                    if (it.mimeType.endsWith(".folder")) {
                        traverseFolder(
                            "https://drive.google.com/drive/folders/${it.id}",
                            if (path.isEmpty()) it.title else "$path/${it.title}",
                            recursionDepth + 1,
                        )
                    }
                }

                pageToken = parsed.nextPageToken
            }
        }

        traverseFolder(parsed.url, "")

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))

    // ============================= Utilities ==============================

    private fun getAllFolderEntries(): List<String> {
        if (preferences.domainList.isBlank()) return emptyList()
        return preferences.domainList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun fetchFolderPage(folderPath: String, pageToken: String): Pair<List<SAnime>, String?> {
        val match = DRIVE_FOLDER_REGEX.matchEntire(folderPath)
            ?: throw Exception("Invalid drive url: $folderPath")
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""

        val request = GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return Pair(emptyList(), null)
        }

        val post = createPost(driveDocument, folderId, pageToken)
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }
        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")

        val animeList = mutableListOf<SAnime>()
        parsed.items.forEach {
            if (it.mimeType.startsWith("video")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=${it.id}",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { s -> formatBytes(s) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
            if (it.mimeType.endsWith(".folder")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        return Pair(animeList, parsed.nextPageToken)
    }

    private fun fetchFolderSearchPage(folderPath: String, query: String, pageToken: String): Pair<List<SAnime>, String?> {
        val match = DRIVE_FOLDER_REGEX.matchEntire(folderPath)
            ?: throw Exception("Invalid drive url: $folderPath")
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""

        val driveDocument = try {
            client.newCall(
                GET("https://drive.google.com/drive/folders/$folderId$recurDepth", headers = getHeaders),
            ).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return Pair(emptyList(), null)
        }

        val cleanQuery = URLEncoder.encode(query, "UTF-8")
        val post = createPost(driveDocument, folderId, pageToken, searchReq(folderId, cleanQuery))
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }
        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")

        val animeList = mutableListOf<SAnime>()
        parsed.items.forEach {
            if (it.mimeType.startsWith("video")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=${it.id}",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { s -> formatBytes(s) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
            if (it.mimeType.endsWith(".folder")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        return Pair(animeList, parsed.nextPageToken)
    }

    // ===================== Multi-folder merge helpers =====================
    // Shared by getPopularAnime and getSearchAnime so "browse everything" and
    // "search everything" behave consistently and don't duplicate the
    // fan-out / merge / retry logic.

    private suspend fun mergeFolderResults(
        page: Int,
        paths: List<String>,
        tokenMap: MutableMap<String, String?>,
        failureMap: MutableMap<String, Int>,
        fetch: suspend (path: String, token: String) -> Pair<List<SAnime>, String?>,
    ): AnimesPage {
        require(paths.isNotEmpty()) { "Enter drive path(s) in extension settings." }

        // Reset per-folder pagination/failure state at the start of a fresh browse.
        if (page == 1) {
            tokenMap.clear()
            failureMap.clear()
            paths.forEach { tokenMap[it] = "" }
        }

        val active = paths.filter { tokenMap[it] != null }
        var lastError: Exception? = null

        // Fan out to every folder in parallel instead of one request at a time.
        val results = coroutineScope {
            active.map { path ->
                async(Dispatchers.IO) {
                    path to runCatching { fetch(path, tokenMap[path] ?: "") }
                }
            }.awaitAll()
        }

        val perFolder = LinkedHashMap<String, List<SAnime>>()
        for ((path, result) in results) {
            result.onSuccess { (items, nextToken) ->
                recordDiscovery(items)
                perFolder[path] = if (preferences.tagSourceFolder) {
                    items.map { it.taggedWithFolder(path) }
                } else {
                    items
                }
                tokenMap[path] = nextToken
                failureMap[path] = 0
            }.onFailure { e ->
                lastError = e as? Exception ?: Exception(e)
                val failures = (failureMap[path] ?: 0) + 1
                failureMap[path] = failures
                // Only give up on a folder after repeated failures, so one
                // dropped connection doesn't permanently drop it from the merge.
                if (failures >= MAX_CONSECUTIVE_FAILURES) tokenMap[path] = null
            }
        }

        // Only surface an error if every folder failed and nothing loaded.
        if (perFolder.values.all { it.isEmpty() } && lastError != null) throw lastError!!

        var merged = when (preferences.mergeOrder) {
            MERGE_ORDER_ROUND_ROBIN -> interleave(paths.mapNotNull { perFolder[it] })
            MERGE_ORDER_SEQUENTIAL -> paths.mapNotNull { perFolder[it] }.flatten()
            else -> perFolder.values.flatten().sortedBy { it.title.lowercase() }
        }

        if (preferences.dedupeMerged) merged = merged.distinctByDriveId()
        merged = withThumbnails(merged)

        return AnimesPage(merged, tokenMap.values.any { it != null })
    }

    private fun interleave(lists: List<List<SAnime>>): List<SAnime> {
        val result = mutableListOf<SAnime>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (list in lists) {
                if (i < list.size) result.add(list[i])
            }
            i++
        }
        return result
    }

    private fun driveIdOf(anime: SAnime): String? {
        val url = runCatching { json.decodeFromString<LinkData>(anime.url).url }.getOrNull() ?: return null
        if ("uc?id=" in url) return url.substringAfter("id=").substringBefore("&")
        return DRIVE_FOLDER_REGEX.matchEntire(url)?.groups?.get("id")?.value
    }

    private fun List<SAnime>.distinctByDriveId(): List<SAnime> {
        val seen = mutableSetOf<String>()
        return filter { anime ->
            val id = driveIdOf(anime) ?: return@filter true // keep it if we can't identify it
            seen.add(id) // false (already present) filters the duplicate out
        }
    }

    private fun SAnime.taggedWithFolder(path: String): SAnime = apply {
        title = "$title  •  ${labelForPath(path)}"
    }

    private fun labelForPath(path: String): String {
        val name = DRIVE_FOLDER_REGEX.matchEntire(path)?.groups?.get("name")
            ?.value?.substringAfter("[")?.substringBeforeLast("]")
        return name ?: runCatching { path.toHttpUrl().encodedPath }.getOrDefault(path)
    }

    // ------------------------- Discovery-time cache ------------------------
    // A small locally-stored history of "first time this extension saw this
    // drive id", used to power the Latest tab and the 🆕 badge. This is NOT
    // Drive's real upload/modified date (nothing in this file exposes one) —
    // it's the extension's own memory of when it first noticed each item.

    private fun loadDiscoveryTimes(): Map<String, Long> {
        val raw = preferences.getString(DISCOVERY_TIMES_KEY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }

    private fun saveDiscoveryTimes(map: Map<String, Long>) {
        runCatching {
            preferences.edit().putString(DISCOVERY_TIMES_KEY, json.encodeToString(map)).apply()
        }
    }

    private fun recordDiscovery(items: List<SAnime>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val discovery = loadDiscoveryTimes().toMutableMap()
        var changed = false
        for (anime in items) {
            val id = driveIdOf(anime) ?: continue
            if (id !in discovery) {
                discovery[id] = now
                changed = true
            }
        }
        if (!changed) return
        // Cap the size so this doesn't grow forever; oldest entries fall off
        // first, which is fine since Latest only cares about the newest ones.
        val trimmed = if (discovery.size > MAX_DISCOVERY_ENTRIES) {
            discovery.entries.sortedByDescending { it.value }
                .take(MAX_DISCOVERY_ENTRIES)
                .associate { it.key to it.value }
        } else {
            discovery
        }
        saveDiscoveryTimes(trimmed)
    }

    private fun SAnime.markedIfNew(discoveredAt: Long): SAnime = apply {
        if (discoveredAt > 0L && System.currentTimeMillis() - discoveredAt < NEW_BADGE_WINDOW_MILLIS) {
            title = "🆕 $title"
        }
    }

    // ---------------------------- List thumbnails ---------------------------
    // Off by default: fetching a cover for every visible folder costs one
    // extra request per item per page on top of everything else here.

    private suspend fun withThumbnails(animes: List<SAnime>): List<SAnime> {
        if (!preferences.loadListThumbnails) return animes
        return coroutineScope {
            animes.map { anime ->
                async(Dispatchers.IO) { fetchThumbnailIfFolder(anime) }
            }.awaitAll()
        }
    }

    private fun fetchThumbnailIfFolder(anime: SAnime): SAnime {
        if (!anime.thumbnail_url.isNullOrEmpty()) return anime

        val parsed = runCatching { json.decodeFromString<LinkData>(anime.url) }.getOrNull() ?: return anime
        if (parsed.type != "multi") return anime // only folders have a cover to look up

        val folderId = runCatching {
            DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value
        }.getOrNull() ?: return anime

        return runCatching {
            val driveDocument = client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
            val coverResponse = client.newCall(
                createPost(driveDocument, folderId, "", searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)),
            ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

            coverResponse.items?.firstOrNull()?.let {
                anime.apply { thumbnail_url = "https://drive.google.com/uc?id=${it.id}" }
            } ?: anime
        }.getOrDefault(anime) // skip a broken lookup rather than failing the whole page
    }

    private fun addSinglePage(folderUrl: String): AnimesPage {
        val match =
            DRIVE_FOLDER_REGEX.matchEntire(folderUrl) ?: throw Exception("Invalid drive url")
        val recurDepth = match.groups["depth"]?.value ?: ""

        val anime = SAnime.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                ?: "Folder"
            url = LinkData(
                "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                "multi",
            ).toJsonString()
            thumbnail_url = ""
        }
        return AnimesPage(listOf(anime), false)
    }

    private fun createPost(
        document: Document,
        folderId: String,
        pageToken: String?,
        getMultiFormPath: (String, String, String) -> String = { folderIdStr, nextPageTokenStr, keyStr ->
            defaultGetRequest(folderIdStr, nextPageTokenStr, keyStr)
        },
    ): Request {
        val keyScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

        val versionScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
        val sapisid =
            client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

        val requestUrl = getMultiFormPath(folderId, pageToken ?: "", key)
        val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", getCookie("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun parsePage(
        request: Request,
        page: Int,
        genMultiFormReq: ((String, String, String) -> String)? = null,
    ): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val recurDepth = request.url.encodedFragment?.let { "#$it" } ?: ""

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(request.url.toString())!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return AnimesPage(emptyList(), false)
        }

        if (page == 1) nextPageToken = ""
        val post = if (genMultiFormReq == null) {
            createPost(driveDocument, folderId, nextPageToken)
        } else {
            createPost(
                driveDocument,
                folderId,
                nextPageToken,
                genMultiFormReq,
            )
        }
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> {
            JSON_REGEX.find(it)!!.groupValues[1]
        }

        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
        parsed.items.forEachIndexed { index, it ->
            if (it.mimeType.startsWith("video")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=${it.id}",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { s -> formatBytes(s) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
            if (it.mimeType.endsWith(".folder")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        nextPageToken = parsed.nextPageToken

        return AnimesPage(animeList, nextPageToken != null)
    }

    // https://github.com/yt-dlp/yt-dlp/blob/8f0be90ecb3b8d862397177bb226f17b245ef933/yt_dlp/extractor/youtube.py#L573
    private fun generateSapisidhashHeader(
        SAPISID: String,
        origin: String = "https://drive.google.com",
    ): String {
        val timeNow = System.currentTimeMillis() / 1000
        // SAPISIDHASH algorithm from https://stackoverflow.com/a/32065323
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun isFolder(text: String) = DRIVE_FOLDER_REGEX matches text

    /*
     * Stolen from the MangaDex manga extension
     *
     * This will likely need to be removed or revisited when the app migrates the
     * extension preferences screen to Compose.
     */
    private fun setupEditTextFolderValidator(editText: EditText) {
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    // Do nothing.
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Do nothing.
                }

                override fun afterTextChanged(editable: Editable?) {
                    requireNotNull(editable)

                    val text = editable.toString()

                    val isValid = text.isBlank() || text
                        .split(";")
                        .map(String::trim)
                        .all(::isFolder)

                    editText.error = if (!isValid) {
                        "${
                            text.split(";").first { !isFolder(it) }
                        } is not a valid google drive folder"
                    } else {
                        null
                    }
                    editText.rootView.findViewById<Button>(android.R.id.button1)
                        ?.isEnabled = editText.error == null
                }
            },
        )
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "domain_list"
        private const val DOMAIN_PREF_DEFAULT = ""

        private const val TRIM_ANIME_KEY = "trim_anime_info"
        private const val TRIM_ANIME_DEFAULT = false

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode_name"
        private const val TRIM_EPISODE_NAME_DEFAULT = true

        private const val TRIM_EPISODE_INFO_KEY = "trim_episode_info"
        private const val TRIM_EPISODE_INFO_DEFAULT = false

        private const val SCANLATOR_ORDER_KEY = "scanlator_order"
        private const val SCANLATOR_ORDER_DEFAULT = false

        private const val MERGE_ORDER_KEY = "merge_order"
        private const val MERGE_ORDER_ALPHABETICAL = "alphabetical"
        private const val MERGE_ORDER_ROUND_ROBIN = "round_robin"
        private const val MERGE_ORDER_SEQUENTIAL = "sequential"
        private const val MERGE_ORDER_DEFAULT = MERGE_ORDER_ALPHABETICAL

        private const val TAG_SOURCE_FOLDER_KEY = "tag_source_folder"
        private const val TAG_SOURCE_FOLDER_DEFAULT = false

        private const val DEDUPE_KEY = "dedupe_merged"
        private const val DEDUPE_DEFAULT = true

        private const val LOAD_THUMBNAILS_KEY = "load_list_thumbnails"
        private const val LOAD_THUMBNAILS_DEFAULT = false

        private const val DISCOVERY_TIMES_KEY = "discovery_times"
        private const val MAX_DISCOVERY_ENTRIES = 1000
        private const val NEW_BADGE_WINDOW_MILLIS = 24L * 60 * 60 * 1000

        private const val MAX_CONSECUTIVE_FAILURES = 3

        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )
        private val KEY_REGEX = Regex(""""(\w{39})"""")
        private val VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private val JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="

        private val ITEM_NUMBER_REGEX = Regex(""" - (?:S\d+E)?(\d+)\b""")
    }

    private val SharedPreferences.domainList
        get() = getString(DOMAIN_PREF_KEY, DOMAIN_PREF_DEFAULT)!!

    private val SharedPreferences.trimAnimeInfo
        get() = getBoolean(TRIM_ANIME_KEY, TRIM_ANIME_DEFAULT)

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    private val SharedPreferences.trimEpisodeInfo
        get() = getBoolean(TRIM_EPISODE_INFO_KEY, TRIM_EPISODE_INFO_DEFAULT)

    private val SharedPreferences.scanlatorOrder
        get() = getBoolean(SCANLATOR_ORDER_KEY, SCANLATOR_ORDER_DEFAULT)

    private val SharedPreferences.mergeOrder
        get() = getString(MERGE_ORDER_KEY, MERGE_ORDER_DEFAULT)!!

    private val SharedPreferences.tagSourceFolder
        get() = getBoolean(TAG_SOURCE_FOLDER_KEY, TAG_SOURCE_FOLDER_DEFAULT)

    private val SharedPreferences.dedupeMerged
        get() = getBoolean(DEDUPE_KEY, DEDUPE_DEFAULT)

    private val SharedPreferences.loadListThumbnails
        get() = getBoolean(LOAD_THUMBNAILS_KEY, LOAD_THUMBNAILS_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Enter drive paths to be shown in extension"
            summary = """Enter links of drive folders to be shown in extension
                |Enter as a semicolon `;` separated list
            """.trimMargin()
            this.setDefaultValue(DOMAIN_PREF_DEFAULT)
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a semicolon.
                |- (optional) Add [] before url to customize name. For example: [drive 5]https://drive.google.com/drive/folders/whatever
                |- (optional) add #<integer> to limit the depth of recursion when loading episodes, defaults is 2. For example: https://drive.google.com/drive/folders/whatever#5
                |- (optional) add #depth,start,stop (all integers) to specify range when loading episodes. Only works if depth is 1. For example: https://drive.google.com/drive/folders/whatever#1,2,6
            """.trimMargin()

            setOnBindEditTextListener(::setupEditTextFolderValidator)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res =
                        preferences.edit().putString(DOMAIN_PREF_KEY, newValue as String).commit()
                    Toast.makeText(
                        screen.context,
                        "Restart App to apply changes",
                        Toast.LENGTH_LONG,
                    ).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_ANIME_KEY
            title = "Trim info from anime titles"
            setDefaultValue(TRIM_ANIME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_NAME_KEY
            title = "Trim info from episode name"
            setDefaultValue(TRIM_EPISODE_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_INFO_KEY
            title = "Trim info from episode info"
            setDefaultValue(TRIM_EPISODE_INFO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SCANLATOR_ORDER_KEY
            title = "Switch order of file path and size"
            setDefaultValue(SCANLATOR_ORDER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = MERGE_ORDER_KEY
            title = "Merge order for multiple folders"
            summary = "How entries from different configured folders are combined"
            entries = arrayOf("Alphabetical", "Round robin (interleaved)", "As configured (no sorting)")
            entryValues = arrayOf(MERGE_ORDER_ALPHABETICAL, MERGE_ORDER_ROUND_ROBIN, MERGE_ORDER_SEQUENTIAL)
            setDefaultValue(MERGE_ORDER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TAG_SOURCE_FOLDER_KEY
            title = "Show source folder in title"
            summary = "Append the configured folder's name to each entry, e.g. \"Title  •  Season 1\""
            setDefaultValue(TAG_SOURCE_FOLDER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DEDUPE_KEY
            title = "Remove duplicate entries"
            summary = "Hide repeated files/folders that show up in more than one configured path"
            setDefaultValue(DEDUPE_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = LOAD_THUMBNAILS_KEY
            title = "Load thumbnails in browse list"
            summary = "Slower, more requests — fetches a cover for every folder shown, not just when opened"
            setDefaultValue(LOAD_THUMBNAILS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
