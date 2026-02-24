package eu.kanade.tachiyomi.animeextension.en.streamporn

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

open class SectionFilter : AnimeFilter.Select<String>(
    "Section",
    arrayOf(
        "All",
        "Movies",
        "Most Viewed",
        "Most Rating",
        "Studios"
    )
) {
    fun getPath(): String {
        return when (state) {
            1 -> "movies"
            2 -> "most-viewed"
            3 -> "most-rating"
            4 -> "studios"
            else -> ""
        }
    }
}

fun getFilterList(): AnimeFilterList {
    return AnimeFilterList(
        SectionFilter()
    )
}
