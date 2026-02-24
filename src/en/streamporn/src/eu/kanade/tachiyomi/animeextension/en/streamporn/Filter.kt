package eu.kanade.tachiyomi.animeextension.en.streamporn

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Section filter – allows browsing different parts of the site.
 * "Studios" is a separate section that lists all studios.
 */
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

/**
 * Returns the complete filter list.
 */
fun getFilterList(): AnimeFilterList {
    return AnimeFilterList(
        SectionFilter()
    )
}
