package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select

internal object EpornerFilters {

    data class Parsed(
        val category: String,
        val duration: String,
        val quality: String,
    )

    fun filterList() = AnimeFilterList(
        Category(),
        Duration(),
        Quality(),
    )

    fun parse(filters: AnimeFilterList): Parsed {
        var category = "all"
        var duration = "0"
        var quality = "0"
        filters.forEach { filter ->
            when (filter) {
                is Category -> category = filter.toUriPart()
                is Duration -> duration = filter.toUriPart()
                is Quality -> quality = filter.toUriPart()
            }
        }
        return Parsed(category, duration, quality)
    }

    abstract class UriPartFilter(name: String, private val values: Array<Pair<String, String>>) :
        Select<String>(name, values.map { it.first }.toTypedArray()) {
        fun toUriPart() = values[state].second
    }

    class Category : UriPartFilter(
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

    class Duration : UriPartFilter(
        "Duration",
        arrayOf(
            "Any" to "0",
            "10+ min" to "10",
            "20+ min" to "20",
            "30+ min" to "30",
        ),
    )

    class Quality : UriPartFilter(
        "Quality",
        arrayOf(
            "Any" to "0",
            "HD 1080" to "1080",
            "HD 720" to "720",
            "HD 480" to "480",
        ),
    )
}
