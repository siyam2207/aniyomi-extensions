package eu.kanade.tachiyomi.animeextension.all.Xmovix

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher
import eu.kanade.tachiyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Xmovix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "xmoviex"
    override val lang = "all"
    override val baseUrl = "https://hd.xmovix.net"
    override val id = 1042024075983L
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val playlistExtractor by lazy { PlaylistUtils(client, headers) }
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/en/$page", headers)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/en/new?page=$page", headers)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val entries = document.select("div.thumbnail").map { element ->
            SAnime.create().apply {
                element.select("a.text-secondary").also {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                }
                thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return AnimesPage(entries, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val movie = filters.firstInstanceOrNull<MovieCategoryFilter>()?.selected
        val country = filters.firstInstanceOrNull<CountryFilter>()?.selected
        val studio = filters.firstInstanceOrNull<StudioFilter>()?.selected
        val scene = filters.firstInstanceOrNull<SceneFilter>()?.state == true
        val top100 = filters.firstInstanceOrNull<Top100Filter>()?.state == true

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                query.isNotBlank() -> {
                    addEncodedPathSegments("en/search")
                    addPathSegment(query.trim())
                }
                movie != null -> addEncodedPathSegments("en/$movie")
                country != null -> addEncodedPathSegments("en/$country")
                studio != null -> addEncodedPathSegments("en/$studio")
                scene -> addEncodedPathSegments("en/porno-video")
                top100 -> addEncodedPathSegments("en/top100.html")
                else -> addEncodedPathSegments("en/new")
            }
            addQueryParameter("page", page.toString())
        }.build().toString()

        return GET(url, headers)
    }

    override fun getFilterList() = getFilters()

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val jpTitle = document.select("div.text-secondary span:contains(title) + span").text()
        val siteCover = document.selectFirst("video.player")?.attr("abs:data-poster")

        return SAnime.create().apply {
            title = document.selectFirst("h1.text-base")?.text().orEmpty()
            genre = document.getInfo("/genres/")
            author = listOfNotNull(
                document.getInfo("/directors/"),
                document.getInfo("/makers/")
            ).joinToString()
            artist = document.getInfo("/actresses/")
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst("div.mb-1")?.text()?.let { append("$it\n") }
                document.getInfo("/labels/")?.let { append("\nLabel: $it") }
                document.getInfo("/series/")?.let { append("\nSeries: $it") }
                document.select("div.text-secondary:not(:has(a)):has(span)")
                    .eachText().forEach { append("\n$it") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                JavCoverFetcher.getCoverByTitle(jpTitle) ?: siteCover
            } else siteCover
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(SEpisode.create().apply {
            url = anime.url
            name = "Episode"
        })
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val playlists = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()?.let(Unpacker::unpack)?.ifEmpty { null } ?: return emptyList()
        val masterPlaylist = playlists.substringAfter("source=\"").substringBefore("\";")
        return playlistExtractor.extractFromHls(masterPlaylist, referer = "$baseUrl/")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("720p", "480p", "360p")
            entryValues = arrayOf("720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException()
    }

    private fun Element.getInfo(urlPart: String) =
        select("div.text-secondary > a[href*=$urlPart]")
            .eachText().joinToString().takeIf(String::isNotBlank)

    private inline fun <reified T> List<*>.firstInstanceOrNull(): T? =
        filterIsInstance<T>().firstOrNull()

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }
}

// Filters.kt

fun getFilters() = AnimeFilterList(
    AnimeFilter.Header("Use filters to narrow results."),
    MovieCategoryFilter(),
    CountryFilter(),
    StudioFilter(),
    SceneFilter(),
    Top100Filter()
)

class MovieCategoryFilter : AnimeFilter.Select<String>(
    "Movie Category", arrayOf(
        "All Movies",
        "News",
        "Movies in FULLHD",
        "Movies in HD",
        "Russian pora movies",
        "Russian translation",
        "Vintage",
        "Parodies"
    )
) {
    private val paths = arrayOf(
        "movies",
        "watch/year/2023",
        "movies/hd1080p",
        "movies/hd720p",
        "russian",
        "withtranslation",
        "vintagexxx",
        "porno-parodies"
    )
    val selected get() = paths[state].takeIf { state != 0 }
}

class CountryFilter : AnimeFilter.Select<String>(
    "Country", arrayOf(
        "None",
        "Italy",
        "Germany",
        "Sweden",
        "Spain",
        "Russia",
        "USA",
        "France",
        "Brazil",
        "Europe"
    )
) {
    private val paths = arrayOf(
        "",
        "watch/country/italy",
        "watch/country/germany",
        "watch/country/sweden",
        "watch/country/spain",
        "watch/country/russia",
        "watch/country/usa",
        "watch/country/france",
        "watch/country/brazil",
        "watch/country/europe"
    )
    val selected get() = paths[state].takeIf { state != 0 }
}

class StudioFilter : AnimeFilter.Select<String>(
    "Studio", arrayOf(
        "None",
        "Marc Dorcel",
        "Wicked Pictures",
        "Hustler",
        "Pure Taboo",
        "Digital Playground",
        "Mario Salieri",
        "Private",
        "New Sensations",
        "Brasileirinhas"
    )
) {
    private val paths = arrayOf(
        "",
        "marc_dorcel",
        "wicked-pictures",
        "hustler",
        "pure-taboo",
        "digital-playground",
        "mario-salieri",
        "private",
        "new-sensations",
        "brasileirinhas"
    )
    val selected get() = paths[state].takeIf { state != 0 }
}

class SceneFilter : AnimeFilter.CheckBox("Scenes Category", false)
class Top100Filter : AnimeFilter.CheckBox("Top 100 Only", false)

