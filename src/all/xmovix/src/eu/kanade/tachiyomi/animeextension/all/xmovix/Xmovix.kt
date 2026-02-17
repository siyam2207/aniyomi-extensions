package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    // Headers matching browser XHR exactly
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36")
        .add("Accept", "*/*")
        .add("Accept-Language", "en")
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)
        .add("Cache-Control", "no-cache")
        .add("Pragma", "no-cache")
        .add("Priority", "u=1, i")
        .add("Sec-CH-UA", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/en/movies/"
        } else {
            "$baseUrl/en/movies/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val animes = parseMovies(document)
        val hasNext = parsePagination(document, isTop100 = false)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ==============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        if (query.isNotBlank()) {
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

        val path = buildPathFromFilters(filters)
        val url = when {
            path == "/en/top.html" -> "$baseUrl$path"
            page == 1 -> "$baseUrl$path"
            else -> "$baseUrl$path/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val requestUrl = response.request.url.toString()

        val animes = if (requestUrl.contains("/top.html")) {
            parseTop100(document)
        } else {
            parseMovies(document)
        }

        val hasNext = if (requestUrl.contains("do=search")) {
            document.select(".infosearch p")?.text()?.let { infoText ->
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
        } else {
            parsePagination(document, requestUrl.contains("/top.html"))
        }

        return AnimesPage(animes, hasNext)
    }

    // ---------- Universal movie/scene parser (div.short) ----------
    private fun parseMovies(document: org.jsoup.nodes.Document): List<SAnime> {
        return document.select("div.short").mapNotNull { element ->
            try {
                val title = element.selectFirst("a.th-title")?.text()
                    ?: element.selectFirst(".title-box .th-title")?.text()
                    ?: element.selectFirst("h3.title")?.text()
                    ?: return@mapNotNull null

                val posterLink = element.selectFirst("a.short-poster") ?: return@mapNotNull null
                val url = posterLink.attr("href")
                if (url.isBlank()) return@mapNotNull null

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

    // ---------- Top 100 parser (li.top100-item) ----------
    private fun parseTop100(document: org.jsoup.nodes.Document): List<SAnime> {
        return document.select("li.top100-item").mapNotNull { element ->
            try {
                val link = element.selectFirst("a") ?: return@mapNotNull null
                val url = link.attr("href")
                if (url.isBlank()) return@mapNotNull null

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

    // ---------- Pagination ----------
    private fun parsePagination(document: org.jsoup.nodes.Document, isTop100: Boolean): Boolean {
        if (isTop100) return false
        if (document.selectFirst("a.next") != null) return true
        if (document.selectFirst("span.pnext a") != null) return true

        val pageLinks = document.select(".navigation a[href*='/page/']")
        if (pageLinks.isNotEmpty()) {
            val currentPage = document.select(".navigation span[class*='current']")?.text()?.toIntOrNull() ?: 1
            val lastPage = pageLinks.mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: currentPage
            return currentPage < lastPage
        }
        return false
    }

    // ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body.string())
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
            artist = actors.takeIf { it.isNotBlank() }
            author = director.takeIf { it.isNotBlank() }
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                url = response.request.url.toString()
            },
        )
    }

    // ============================== VIDEOS ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body.string())
        val videos = mutableListOf<Video>()

        // Extract embed URLs from the movie page's click handlers
        document.select("div.fplay.tabs-b.video-box").forEach { container ->
            val containerDiv = container.selectFirst("div[id]") ?: return@forEach
            val containerId = containerDiv.id()

            val script = document.select("script")
                .firstOrNull { it.data().contains("'click', '#$containerId'") }
                ?.data()
                ?: return@forEach

            val embedUrl = Regex("""s2\.src\s*=\s*["']([^"']+)["']""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?: return@forEach

            // Only handle filmcdm.top for now
            if ("filmcdm.top" in embedUrl) {
                val resolved = extractFilmCdmMaster(embedUrl, response.request.url.toString())
                videos.addAll(resolved)
            }
        }

        return videos.distinctBy { it.url }
    }

    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""

    // ---------- Improved filmcdm.top HLS extractor ----------
    private fun extractFilmCdmMaster(embedUrl: String, referer: String): List<Video> {
        val headers = headersBuilder()
            .add("Referer", referer)
            .build()

        // 1. Fetch embed page
        val document = client.newCall(GET(embedUrl, headers)).execute().use { response ->
            Jsoup.parse(response.body.string())
        }

        // 2. Find script with packed player config (contains jwplayer)
        val script = document.select("script").firstOrNull { it.data().contains("jwplayer") }?.data()
            ?: return emptyList()

        // 3. Extract the var p object (it may be "g p=" or "var p=")
        val pObjectRegex = Regex("""(?:var|g)\s+p\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val pObjectMatch = pObjectRegex.find(script) ?: return emptyList()
        val pObjectStr = pObjectMatch.groupValues[1]

        // 4. Extract the three obfuscated URLs from the object
        val urlPattern = Regex("""["']1[ci]?["']\s*:\s*"([^"]+)"""")
        val obfuscated = urlPattern.findAll(pObjectStr).map { it.groupValues[1] }.toList()
        if (obfuscated.isEmpty()) return emptyList()

        // 5. Decode (shift each letter back by 1, "1g://" → "https://")
        fun decode(obfuscated: String): String {
            return obfuscated.map { char ->
                when {
                    char.isDigit() -> char
                    char in 'a'..'z' -> (char.code - 1).toChar()
                    char in 'A'..'Z' -> (char.code - 1).toChar()
                    else -> char
                }
            }.joinToString("").replace("1g://", "https://")
        }

        val candidates = obfuscated.map { decode(it) }

        // 6. Try each candidate – find the master.m3u8
        for (candidate in candidates) {
            try {
                val playlistResponse = client.newCall(GET(candidate, headers)).execute()
                if (playlistResponse.isSuccessful) {
                    val playlistBody = playlistResponse.body.string()
                    playlistResponse.close()

                    if (playlistBody.contains("#EXTM3U")) {
                        val videos = mutableListOf<Video>()
                        val lines = playlistBody.lines()
                        var i = 0
                        while (i < lines.size) {
                            val line = lines[i]
                            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                                val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1) ?: "unknown"
                                val quality = when {
                                    resolution.contains("1920") -> "1080p"
                                    resolution.contains("1280") -> "720p"
                                    resolution.contains("852") -> "480p"
                                    else -> resolution
                                }
                                i++
                                if (i < lines.size) {
                                    val segmentPlaylist = lines[i].trim()
                                    if (segmentPlaylist.isNotBlank() && !segmentPlaylist.startsWith("#")) {
                                        val baseUrl = candidate.substringBeforeLast("/") + "/"
                                        val playlistUrl = baseUrl + segmentPlaylist
                                        videos.add(Video(playlistUrl, "HLS • $quality", playlistUrl, headers))
                                    }
                                }
                            }
                            i++
                        }
                        if (videos.isNotEmpty()) return videos
                        // Fallback to master URL itself
                        return listOf(Video(candidate, "HLS • Auto", candidate, headers))
                    }
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }

        return emptyList()
    }

    // ============================== FILTERS ==============================
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
        filters.forEach { filter ->
            if (filter is Top100Filter && filter.state) return "/en/top.html"
        }
        filters.forEach { filter ->
            if (filter is ScenesFilter && filter.state) return "/en/porno-video/"
        }

        var path = "/en/movies/"
        filters.forEach { filter ->
            when (filter) {
                is MoviesFilter -> path = filter.getPath()
                is CountryFilter -> if (filter.state != 0) path = filter.getPath()
                is StudioFilter -> if (filter.state != 0) path = filter.getPath()
                else -> {}
            }
        }
        return path
    }

    // ----- Filter classes (single space before // comments) -----
    private class ScenesFilter : AnimeFilter.CheckBox("Scenes")
    private class Top100Filter : AnimeFilter.CheckBox("Top 100")

    private class MoviesFilter : AnimeFilter.Select<String>(
        "Movies",
        arrayOf(
            "All Movies", // 0
            "News", // 1
            "Movies in FullHD", // 2
            "Movies in HD", // 3
            "Russian porn movies", // 4
            "Russian translation", // 5
            "Vintage", // 6
            "Parodies", // 7
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
            "None", // 0
            "Italy", // 1
            "USA", // 2
            "Germany", // 3
            "France", // 4
            "Sweden", // 5
            "Brazil", // 6
            "Spain", // 7
            "Europe", // 8
            "Russia", // 9
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
            "None", // 0
            "Marc Dorcel", // 1
            "Wicked Pictures", // 2
            "Hustler", // 3
            "Daring", // 4
            "Pure Taboo", // 5
            "Digital Playground", // 6
            "Mario Salieri", // 7
            "Private", // 8
            "New Sensations", // 9
            "Brasileirinhas", // 10
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
}
