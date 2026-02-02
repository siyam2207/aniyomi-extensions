package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal object EpornerFilters {

    fun filterList() = AnimeFilterList(
        CategoryFilter(),
        DurationFilter(),
        QualityFilter(),
    )

    fun parse(filters: AnimeFilterList): Parsed {
        var category = "all"
        var duration = "0"
        var quality = "0"

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> if (filter.state != 0) category = filter.toUriPart()
                is DurationFilter -> if (filter.state != 0) duration = filter.toUriPart()
                is QualityFilter -> if (filter.state != 0) quality = filter.toUriPart()
            }
        }

        return Parsed(category, duration, quality)
    }

    internal data class Parsed(
        val category: String,
        val duration: String,
        val quality: String,
    )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter : UriPartFilter(
        "Category",
        arrayOf(
            "All" to "all",
            "Anal" to "anal",
            "Asian" to "asian",
            "Big Dick" to "big-dick",
            "Blowjob" to "blowjob",
            "Brunette" to "brunette",
            "Creampie" to "creampie",
            "Cumshot" to "cumshot",
            "Doggystyle" to "doggystyle",
            "Ebony" to "ebony",
            "Facial" to "facial",
            "Gangbang" to "gangbang",
            "HD" to "hd",
            "Interracial" to "interracial",
            "Lesbian" to "lesbian",
            "Masturbation" to "masturbation",
            "Mature" to "mature",
            "Milf" to "milf",
            "Teen" to "teen",
        ),
    )

    private class DurationFilter : UriPartFilter(
        "Duration",
        arrayOf(
            "Any" to "0",
            "10+ min" to "10",
            "20+ min" to "20",
            "30+ min" to "30",
        ),
    )

    private class QualityFilter : UriPartFilter(
        "Quality",
        arrayOf(
            "Any" to "0",
            "HD 1080" to "1080",
            "HD 720" to "720",
            "HD 480" to "480",
        ),
    )
}
