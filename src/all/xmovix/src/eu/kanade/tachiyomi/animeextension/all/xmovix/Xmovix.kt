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
import org.jsoup.nodes.Element

class Xmovix : AnimeHttpSource() {

    override val name = "Xmovix"
    override val baseUrl = "https://hd.xmovix.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request {
        val filters = getFilterList()
        val path = buildPathFromFilters(filters)
        return GET("$baseUrl$path?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body.string())
        val filters = getFilterList()

        val isScenes = filters.any { it is ScenesFilter && it.state }
        val isTop100 = filters.any { it is Top100Filter && it.state }

        val animes = when {
            isScenes -> parseScenes(document)
            else -> parseMovies(document)
        }

        val hasNext = parsePagination(document, isScenes)
        return AnimesPage(animes, hasNext)
    }

    private fun parseMovies(document: org.jsoup.nodes.Document): List<SAnime> {
        return document.select("div.short").mapNotNull { element ->
            val title = element.selectFirst("a.th-title")?.text() ?: return@mapNotNull null
            val posterLink = element.selectFirst("a.short-poster") ?: return@mapNotNull null
            val img = posterLink.selectFirst("img")
            val thumbnail = when {
                img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
                else -> img?.attr("data-src")
            } ?: return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(posterLink.attr("href"))
                thumbnail_url = thumbnail
            }
        }
    }

    private fun parseScenes(document: org.jsoup.nodes.Document): List<SAnime> {
        return document.select("div.collections.t2").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val title = element.selectFirst(".collections__title")?.text() ?: return@mapNotNull null
            val img = element.selectFirst(".collections__image")
            val thumbnail = when {
                img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
                else -> img?.attr("data-src")
            } ?: return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = thumbnail
            }
        }
    }

    private fun parsePagination(document: org.jsoup.nodes.Document, isScenes: Boolean): Boolean {
        return when {
            document.selectFirst("a.next") != null -> true
            document.selectFirst("a[title='Load More']") != null -> true
            document.select(".navigation a[href*='page=']").any { it.text().toIntOrNull() ?: 0 > 0 } -> {
                val currentPage = document.select(".navigation span[class*='current']")?.text()?.toIntOrNull() ?: 1
                val lastPage = document.select(".navigation a[href*='page=']")
                    .mapNotNull { it.text().toIntOrNull() }
                    .maxOrNull() ?: currentPage
                currentPage < lastPage
            }
            else -> false
        }
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
        val document = Jsoup.parse(response.body.string())
        val animes = document.select("div.short").mapNotNull { element ->
            popularAnimeFromElementOrNull(element)
        }

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

    private fun popularAnimeFromElementOrNull(element: Element): SAnime? {
        val title = element.selectFirst("a.th-title")?.text() ?: return null
        val posterLink = element.selectFirst("a.short-poster") ?: return null
        val img = posterLink.selectFirst("img")
        val thumbnail = when {
            img?.hasAttr("src") == true && !img.attr("src").startsWith("data:") -> img.attr("src")
            else -> img?.attr("data-src")
        } ?: return null

        return SAnime.create().apply {
            this.title = title
            setUrlWithoutDomain(posterLink.attr("href"))
            thumbnail_url = thumbnail
        }
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

    // ============================== Videos ==============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun videoUrlParse(response: Response): String = ""

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
        // Priority: Scenes > Top 100 > Studio > Country > Movies
        for (filter in filters) {
            when (filter) {
                is ScenesFilter -> if (filter.state) return "/en/porn-scenes/"
                is Top100Filter -> if (filter.state) return "/en/top100.html"
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

        return "/en"
    }

    // ----- Individual Filter Classes – EXACT menu order, no multi‑spaces -----
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
            0 -> "/en"
            1 -> "/en/watch/year/2025"
            2 -> "/en/movies/hd-1080p"
            3 -> "/en/movies/hd-720p"
            4 -> "/en/russian"
            5 -> "/en/with-translation"
            6 -> "/en/vintagexxx"
            7 -> "/en/porno-parodies"
            else -> "/en"
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
            1 -> "/en/watch/country/italy"
            2 -> "/en/watch/country/usa"
            3 -> "/en/watch/country/germany"
            4 -> "/en/watch/country/france"
            5 -> "/en/watch/country/sweden"
            6 -> "/en/watch/country/brazil"
            7 -> "/en/watch/country/spain"
            8 -> "/en/watch/country/europe"
            9 -> "/en/watch/country/russia"
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
            1 -> "/en/marc_dorcel"
            2 -> "/en/wicked-pictures"
            3 -> "/en/hustler"
            4 -> "/en/daring"
            5 -> "/en/pure-taboo"
            6 -> "/en/digital-playground"
            7 -> "/en/mario-salieri"
            8 -> "/en/private"
            9 -> "/en/new-sensations"
            10 -> "/en/brasileirinhas"
            else -> ""
        }
    }
}
