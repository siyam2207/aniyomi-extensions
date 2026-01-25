// Filters.kt
package eu.kanade.tachiyomi.extension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Filter

// Order filter matching API's order parameter[citation:1]
class OrderFilter : Filter.Select<String>(
    "Sort by",
    arrayOf(
        "latest",
        "longest", 
        "shortest",
        "top-rated",
        "most-popular",
        "top-weekly",
        "top-monthly"
    )
)

// Category filter for common categories[citation:2]
class CategoryFilter : Filter.Select<String>(
    "Category",
    arrayOf(
        "",
        "anal",
        "teen", 
        "milf",
        "big-tits",
        "amateur",
        "hardcore",
        "blowjob",
        "creampie",
        "asian",
        "blonde",
        "latina",
        "bbc",
        "lesbian",
        "threesome",
        "hd-1080p"
    )
)

// Quality filter (lq parameter)[citation:1]
class QualityFilter : Filter.CheckBox("Exclude low quality", false)

// Thumbnail size filter[citation:1]
class ThumbSizeFilter : Filter.Select<String>(
    "Thumbnail size",
    arrayOf("medium", "small", "big")
)

// Gay content filter[citation:1]
class GayContentFilter : Filter.Select<String>(
    "Gay content",
    arrayOf("excluded", "included", "only gay")
)

// Factory function to create filter list
fun getFilterList() = AnimeFilterList(
    OrderFilter(),
    CategoryFilter(),
    QualityFilter(),
    ThumbSizeFilter(),
    GayContentFilter()
)
