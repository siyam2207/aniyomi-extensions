package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animeextension.filter.AnimeFilter
import eu.kanade.tachiyomi.animeextension.filter.AnimeFilterList

object EpornerFilters {

    fun getFilters(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        CategoryFilter(),
    )

    fun applyFilters(builder: StringBuilder, filters: AnimeFilterList) {
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) {
                        builder.append("&sort=$value")
                    }
                }

                is CategoryFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) {
                        builder.append("&category=$value")
                    }
                }
            }
        }
    }

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort",
        arrayOf(
            "Top" to "top",
            "Latest" to "latest",
            "Longest" to "longest",
        ),
    )

    private class CategoryFilter : AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "All" to "",
            "Amateur" to "amateur",
            "Anal" to "anal",
            "Asian" to "asian",
            "Big Tits" to "big-tits",
        ),
    )
}
