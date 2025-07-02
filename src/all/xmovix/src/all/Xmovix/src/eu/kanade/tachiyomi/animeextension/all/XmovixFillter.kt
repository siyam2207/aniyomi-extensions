package eu.kanade.tachiyomi.animeextension.all.xmovix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

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
