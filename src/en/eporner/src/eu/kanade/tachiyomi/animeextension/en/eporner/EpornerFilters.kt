package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animeextension.filter.AnimeFilter
import eu.kanade.tachiyomi.animeextension.filter.AnimeFilterList

object EpornerFilters {

    private val CATEGORIES = listOf(
        "Amateur" to "amateur",
        "Anal" to "anal",
        "Asian" to "asian",
        "Big Tits" to "big-tits",
        "Blowjob" to "blowjob",
        "Lesbian" to "lesbian",
        "MILF" to "milf",
        "Teen" to "teen",
    )

    fun getFilters() = AnimeFilterList(
        SortFilter(),
        CategoryFilter(),
        OrientationFilter(),
        MinDurationFilter(),
        MaxDurationFilter(),
        HdOnlyFilter(),
    )

    fun applyFilters(builder: StringBuilder, filters: AnimeFilterList) {
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> filter.value?.let { builder.append("&sort=$it") }
                is CategoryFilter -> filter.value?.let { builder.append("&category=$it") }
                is OrientationFilter -> filter.value?.let { builder.append("&orientation=$it") }
                is MinDurationFilter -> filter.value?.let { builder.append("&min_duration=$it") }
                is MaxDurationFilter -> filter.value?.let { builder.append("&max_duration=$it") }
                is HdOnlyFilter -> if (filter.state) builder.append("&hd=1")
            }
        }
    }

    class SortFilter : AnimeFilter.Select<String>(
        "Sort",
        arrayOf("Popular", "Latest"),
    ) {
        val value get() = if (state == 0) "top" else "latest"
    }

    class CategoryFilter : AnimeFilter.Select<String>(
        "Category",
        arrayOf("Any") + CATEGORIES.map { it.first },
    ) {
        val value get() = CATEGORIES.getOrNull(state - 1)?.second
    }

    class OrientationFilter : AnimeFilter.Select<String>(
        "Orientation",
        arrayOf("Any", "Straight", "Gay"),
    ) {
        val value get() = when (state) {
            1 -> "straight"
            2 -> "gay"
            else -> null
        }
    }

    class MinDurationFilter : AnimeFilter.Select<String>(
        "Min Duration (min)",
        arrayOf("Any", "5", "10", "20", "30"),
    ) {
        val value get() = getOrNull(state)
    }

    class MaxDurationFilter : AnimeFilter.Select<String>(
        "Max Duration (min)",
        arrayOf("Any", "10", "20", "30", "60"),
    ) {
        val value get() = getOrNull(state)
    }

    class HdOnlyFilter : AnimeFilter.CheckBox("HD only")
}
