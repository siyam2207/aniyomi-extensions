package eu.kanade.tachiyomi.animeextension.en.xmovix

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Random

class Xmovix : ParsedAnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    // Initialize the inner extractor
    private val myVidPlayExtractor by lazy { MyVidPlayExtractor(client) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular (Filters) ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val filters = getFilterList()
        val path = buildPathFromFilters(filters)
        val url = when {
            path == "/en/top.html" -> "$baseUrl$path"
            page == 1 -> "$baseUrl$path"
            else -> "$baseUrl$path/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val filters = getFilterList()
        val isTop100 = filters.any { it is Top100Filter && it.state }

        val animes = if (isTop100) parseTop100(document) else parseMovies(document)
        val hasNext = parsePagination(document, isTop100)
        return AnimesPage(animes, hasNext)
    }

    private fun parseMovies(doc: Document): List<SAnime> {
        return doc.select("div.short").mapNotNull { element ->
            try {
                val title = element.selectFirst("a.th-title")?.text()
                    ?: element.selectFirst(".title-box .th-title")?.text()
                    ?: element.selectFirst("h3.title")?.text()
                    ?: return@mapNotNull null

                val posterLink = element.selectFirst("a.short-poster") ?: return@mapNotNull null
                val url = posterLink.attr("href").ifEmpty { return@mapNotNull null }
                val img = posterLink.selectFirst("img")
                val thumbnail = when {
                    img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
                    else -> img?.attr("data-src")
                } ?: img?.attr("src") ?: return@mapNotNull null

                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(url)
                    thumbnail_url = thumbnail
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseTop100(doc: Document): List<SAnime> {
        return doc.select("li.top100-item").mapNotNull { element ->
            try {
                val link = element.selectFirst("a") ?: return@mapNotNull null
                val url = link.attr("href").ifEmpty { return@mapNotNull null }
                val title = element.selectFirst(".top100-name")?.text() ?: return@mapNotNull null
                val img = element.selectFirst(".top100-img img")
                val thumbnail = when {
                    img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
                    else -> img?.attr("data-src")
                } ?: img?.attr("src") ?: return@mapNotNull null

                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(url)
                    thumbnail_url = thumbnail
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parsePagination(doc: Document, isTop100: Boolean): Boolean {
        if (isTop100) return false
        if (doc.selectFirst("a.next") != null) return true
        if (doc.selectFirst("span.pnext a") != null) return true

        val pageLinks = doc.select(".navigation a[href*='/page/']")
        if (pageLinks.isNotEmpty()) {
            val currentPage = doc.select(".navigation span[class*='current']")?.text()?.toIntOrNull() ?: 1
            val lastPage = pageLinks.mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: currentPage
            return currentPage < lastPage
        }
        return false
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBody = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("story", query)
            .add("search_start", ((page - 1) * 24).toString())
            .add("full_search", "0")
            .add("result_from", ((page - 1) * 24 + 1).toString())
            .build()

        return Request.Builder()
            .url("$baseUrl/index.php?do=search&lang=en")
            .headers(headers)
            .post(formBody)
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = parseMovies(document)

        val hasNext = document.select(".infosearch p")?.text()?.let { infoText ->
            val regex = Regex("""results (\d+)-(\d+) of (\d+)""")
            val match = regex.find(infoText)
            if (match != null) {
                val currentEnd = match.groupValues[2].toIntOrNull() ?: 0
                val total = match.groupValues[3].toIntOrNull() ?: 0
                currentEnd < total
            } else {
                false
            }
        } ?: false

        return AnimesPage(animes, hasNext)
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            AnimeFilter.Header("🎬 Scenes & Top Lists"),
            ScenesFilter(),
            Top100Filter(),
            AnimeFilter.Header("🎥 Movies"),
            MoviesFilter(),
            AnimeFilter.Header("🌍 Country"),
            CountryFilter(),
            AnimeFilter.Header("🏢 Studio"),
            StudioFilter(),
        )
    }

    private fun buildPathFromFilters(filters: AnimeFilterList): String {
        for (filter in filters) {
            when (filter) {
                is ScenesFilter -> if (filter.state) return "/en/porno-video/"
                is Top100Filter -> if (filter.state) return "/en/top.html"
                else -> {}
            }
        }
        for (filter in filters) {
            when (filter) {
                is StudioFilter -> if (filter.state != 0) return filter.getPath()
                is CountryFilter -> if (filter.state != 0) return filter.getPath()
                is MoviesFilter -> return filter.getPath()
                else -> {}
            }
        }
        return "/en/movies/"
    }

    private class ScenesFilter : AnimeFilter.CheckBox("Scenes")
    private class Top100Filter : AnimeFilter.CheckBox("Top 100")

    private class MoviesFilter : AnimeFilter.Select<String>(
        "Movies",
        arrayOf(
            "All Movies",
            "News",
            "Movies in FullHD",
            "Movies in HD",
            "Russian porn movies",
            "Russian translation",
            "Vintage",
            "Parodies",
        ),
    ) {
        fun getPath(): String = when (state) {
            0 -> "/en/movies/"
            1 -> "/en/watch/year/2025/"
            2 -> "/en/movies/hd-1080p/"
            3 -> "/en/movies/hd-720p/"
            4 -> "/en/russian/"
            5 -> "/en/with-translation/"
            6 -> "/en/vintagexxx/"
            7 -> "/en/porno-parodies/"
            else -> "/en/movies/"
        }
    }

    private class CountryFilter : AnimeFilter.Select<String>(
        "Country",
        arrayOf(
            "None",
            "Italy",
            "USA",
            "Germany",
            "France",
            "Sweden",
            "Brazil",
            "Spain",
            "Europe",
            "Russia",
        ),
    ) {
        fun getPath(): String = when (state) {
            0 -> ""
            1 -> "/en/watch/country/italy/"
            2 -> "/en/watch/country/usa/"
            3 -> "/en/watch/country/germany/"
            4 -> "/en/watch/country/france/"
            5 -> "/en/watch/country/sweden/"
            6 -> "/en/watch/country/brazil/"
            7 -> "/en/watch/country/spain/"
            8 -> "/en/watch/country/europe/"
            9 -> "/en/watch/country/russia/"
            else -> ""
        }
    }

    private class StudioFilter : AnimeFilter.Select<String>(
        "Studio",
        arrayOf(
            "None",
            "Marc Dorcel",
            "Wicked Pictures",
            "Hustler",
            "Daring",
            "Pure Taboo",
            "Digital Playground",
            "Mario Salieri",
            "Private",
            "New Sensations",
            "Brasileirinhas",
        ),
    ) {
        fun getPath(): String = when (state) {
            0 -> ""
            1 -> "/en/movies/marc_dorcel/"
            2 -> "/en/movies/wicked-pictures/"
            3 -> "/en/movies/hustler/"
            4 -> "/en/movies/daring/"
            5 -> "/en/movies/pure-taboo/"
            6 -> "/en/movies/digital-playground/"
            7 -> "/en/movies/mario-salieri/"
            8 -> "/en/movies/private/"
            9 -> "/en/movies/new-sensations/"
            10 -> "/en/movies/brasileirinhas/"
            else -> ""
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1 span[itemprop=name]")?.text()
                ?: document.selectFirst("h1")?.ownText()
                ?: ""

            thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            val descriptionParts = mutableListOf<String>()

            document.selectFirst("div#s-desc h2")?.text()?.takeIf { it.isNotBlank() }?.let {
                descriptionParts.add(it)
            }

            val director = document.select("span[itemprop=director] a").joinToString { it.text() }
            if (director.isNotBlank()) {
                descriptionParts.add("🎬 Director: $director")
                author = director
            }

            document.selectFirst("li:contains(Duration:)")?.ownText()?.takeIf { it.isNotBlank() }?.let {
                descriptionParts.add("⏱️ Duration: $it")
            }

            val year = document.selectFirst("span[itemprop=dateCreated] a")?.text()
            val country = document.select(".parameters-info span.str675")?.getOrNull(1)?.text()
            if (year != null || country != null) {
                descriptionParts.add("📅 ${year ?: ""} ${country ?: ""}".trim())
            }

            val actors = document.select("span[itemprop=actors] a").joinToString { it.text() }
            if (actors.isNotBlank()) {
                descriptionParts.add("🎭 Cast: $actors")
                artist = actors
            }

            val tags = document.select("ul.flist-col3 a[href*=/tags/]").joinToString(", ") { it.text() }
            if (tags.isNotBlank()) {
                descriptionParts.add("🏷️ Tags: $tags")
                genre = tags
            }

            document.selectFirst("li:contains(Maker:)")?.ownText()?.takeIf { it.isNotBlank() }?.let {
                descriptionParts.add("🏢 Studio: $it")
            }

            if (descriptionParts.isEmpty()) {
                document.selectFirst("meta[name=description]")?.attr("content")?.let {
                    descriptionParts.add(it)
                }
            }

            description = descriptionParts.joinToString("\n\n")
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return listOf(
            SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                url = document.location()
            },
        )
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Extract all s2.src assignments from <script> tags
        val embedUrls = mutableListOf<String>()
        document.select("script").forEach { script ->
            val data = script.data()
            val match = Regex("""s2\.src\s*=\s*["']([^"']+)["']""").findAll(data)
            match.forEach { embedUrls.add(it.groupValues[1]) }
        }

        Log.d("Xmovix", "Found embed URLs: $embedUrls")

        // Fallback to direct video/iframe if no s2.src found
        if (embedUrls.isEmpty()) {
            Log.d("Xmovix", "No s2.src found, falling back to direct video/iframe")
            val videoSrc = document.selectFirst("video source[src]")?.attr("src")
                ?: document.selectFirst("video[src]")?.attr("src")
                ?: document.selectFirst("iframe[src*='player']")?.attr("src")
                ?: return emptyList()
            val videoUrl = if (videoSrc.startsWith("http")) videoSrc else "$baseUrl$videoSrc"
            return listOf(Video(videoUrl, "Default", videoUrl))
        }

        // Delegate to MyVidPlayExtractor only
        embedUrls.forEach { url ->
            if (url.contains("myvidplay.com")) {
                Log.d("Xmovix", "Processing myvidplay.com URL: $url")
                val videos = myVidPlayExtractor.videosFromUrl(url, "MyVidPlay")
                if (videos.isNotEmpty()) {
                    Log.d("Xmovix", "Extractor returned ${videos.size} videos")
                } else {
                    Log.e("Xmovix", "Extractor returned no videos for $url")
                }
                videoList.addAll(videos)
            }
            // filmcdm.top is intentionally ignored
        }

        return videoList
    }

    // Not used because we override videoListParse
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector(): String? = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException()
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String? = throw UnsupportedOperationException()
    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ====================== MyVidPlay Extractor (Inner Class) with Correct Token Extraction ======================
    private inner class MyVidPlayExtractor(private val client: okhttp3.OkHttpClient) {

        private val random = Random()
        private val tag = "MyVidPlayExtractor"

        fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
            val video = videoFromUrl(url, prefix)
            return video?.let(::listOf) ?: emptyList()
        }

        private fun videoFromUrl(url: String, prefix: String = ""): Video? {
            return runCatching {
                Log.d(tag, "Starting extraction for: $url")

                val embedHeaders = Headers.Builder()
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
                    .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .set("Accept-Language", "en-US,en;q=0.5")
                    .set("Referer", "https://myvidplay.com/")
                    .set("DNT", "1")
                    .build()

                val response = client.newCall(GET(url, embedHeaders)).execute()
                val newUrl = response.request.url.toString()
                val host = getBaseUrl(newUrl)
                val html = response.body.string()
                Log.d(tag, "Page HTML snippet (first 2000 chars): ${html.take(2000)}")

                if (!html.contains("/pass_md5/")) {
                    Log.e(tag, "HTML does not contain /pass_md5/")
                    return null
                }

                // Extract token from URL parameter (e.g., ?token=...)
                var token = extractToken(html)
                if (token == null) {
                    Log.d(tag, "Token not found via parameter, trying from pass_md5 path")
                    token = extractTokenFromPath(html)
                }
                Log.d(tag, "Extracted token: $token")
                if (token == null) {
                    Log.e(tag, "Token not found")
                    return null
                }

                // Extract expiry – if not found, use "None" (Python script uses None)
                var expiry = extractExpiry(html)
                if (expiry == null) {
                    Log.d(tag, "Expiry not found in HTML, using 'None' as fallback (server accepts this)")
                    expiry = "None"
                }
                Log.d(tag, "Using expiry: $expiry")

                val passPath = extractPassPath(html)
                Log.d(tag, "Extracted passPath: $passPath")
                if (passPath == null) {
                    Log.e(tag, "pass_md5 path not found")
                    return null
                }

                val passUrl = "$host$passPath"
                Log.d(tag, "passUrl: $passUrl")

                val ajaxHeaders = Headers.Builder()
                    .set("User-Agent", embedHeaders["User-Agent"]!!)
                    .set("Referer", url)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()

                val passResponse = client.newCall(GET(passUrl, ajaxHeaders)).execute()
                val baseVideoUrl = passResponse.body.string().trim()
                Log.d(tag, "Base video URL: $baseVideoUrl")

                if (baseVideoUrl == "RELOAD") {
                    Log.e(tag, "Server requested RELOAD")
                    return null
                }

                val randomString = createRandomString(10)
                val finalUrl = "$baseVideoUrl$randomString?token=$token&expiry=$expiry"
                Log.d(tag, "Final URL: $finalUrl")

                val videoHeaders = Headers.Builder()
                    .set("User-Agent", embedHeaders["User-Agent"]!!)
                    .set("Referer", url)
                    .set("Accept", "*/*")
                    .build()

                val quality = extractQuality(html) ?: "MP4"
                val videoName = if (prefix.isNotEmpty()) "$prefix MyVidPlay" else "MyVidPlay"

                Log.d(tag, "Returning Video object with URL: $finalUrl, quality: $quality")

                Video(finalUrl, "$videoName - $quality", finalUrl, videoHeaders)
            }.onFailure { e ->
                Log.e(tag, "Exception in videoFromUrl", e)
            }.getOrNull()
        }

        private fun extractToken(html: String): String? {
            val tokenRegex = """token=([^&"'\\s]+)""".toRegex()
            return tokenRegex.find(html)?.groupValues?.get(1)
        }

        private fun extractTokenFromPath(html: String): String? {
            // Find the whole path after /pass_md5/ up to the closing quote
            val pathRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
            pathRegex.find(html)?.groupValues?.get(1)?.let { fullPath ->
                val parts = fullPath.split('/')
                if (parts.size >= 3) {
                    // The token is the last segment
                    return parts.last()
                }
            }
            // Fallback: look for "/pass_md5/..." inside quotes
            val fallbackRegex = """['"]/pass_md5/([^'"]+)['"]""".toRegex()
            fallbackRegex.find(html)?.groupValues?.get(1)?.let { fullPath ->
                val parts = fullPath.split('/')
                if (parts.size >= 3) {
                    return parts.last()
                }
            }
            return null
        }

        private fun extractExpiry(html: String): String? {
            val expiryRegex = """expiry=(\d+)""".toRegex()
            expiryRegex.find(html)?.groupValues?.get(1)?.let { return it }

            val jsonExpiry = """"expiry":\s*(\d+)""".toRegex()
            jsonExpiry.find(html)?.groupValues?.get(1)?.let { return it }

            return null
        }

        private fun extractPassPath(html: String): String? {
            val passRegex = """\.get\('(/pass_md5/[^']+)'""".toRegex()
            passRegex.find(html)?.groupValues?.get(1)?.let { return it }

            val fallbackRegex = """['"]/pass_md5/([^'"]+)['"]""".toRegex()
            fallbackRegex.find(html)?.groupValues?.get(0)?.let { return it }

            return null
        }

        private fun extractQuality(html: String): String? {
            return Regex("\\d{3,4}p")
                .find(html.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)
        }

        private fun getBaseUrl(url: String): String {
            return URI(url).let {
                "${it.scheme}://${it.host}"
            }
        }

        private fun createRandomString(length: Int): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..length)
                .map { chars[random.nextInt(chars.length)] }
                .joinToString("")
        }
    }
}
