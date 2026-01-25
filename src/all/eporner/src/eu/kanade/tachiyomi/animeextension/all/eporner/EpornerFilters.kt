package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

// ==================== FILTER SYSTEM ====================

fun getFilters() = AnimeFilterList(
    // Sort Filter
    AnimeFilter.Header("SORTING"),
    SortFilter(),
    AnimeFilter.Separator(),
    
    // Main Categories
    AnimeFilter.Header("MAIN CATEGORIES"),
    CategoryFilter("Amateur", "amateur"),
    CategoryFilter("Anal", "anal"),
    CategoryFilter("Asian", "asian"),
    CategoryFilter("BBC", "bbc"),
    CategoryFilter("BBW", "bbw"),
    CategoryFilter("BDSM", "bdsm"),
    CategoryFilter("Big Tits", "big-tits"),
    CategoryFilter("Blonde", "blonde"),
    CategoryFilter("Blowjob", "blowjob"),
    CategoryFilter("Brunette", "brunette"),
    AnimeFilter.Separator(),
    
    // More Categories
    AnimeFilter.Header("MORE CATEGORIES"),
    CategoryFilter("Bukkake", "bukkake"),
    CategoryFilter("College", "college"),
    CategoryFilter("Creampie", "creampie"),
    CategoryFilter("Cumshot", "cumshot"),
    CategoryFilter("Double Penetration", "double-penetration"),
    CategoryFilter("Ebony", "ebony"),
    CategoryFilter("Gangbang", "gangbang"),
    CategoryFilter("Hardcore", "hardcore"),
    CategoryFilter("Japanese", "japanese"),
    AnimeFilter.Separator(),
    
    // Additional Categories
    AnimeFilter.Header("ADDITIONAL CATEGORIES"),
    CategoryFilter("Latina", "latina"),
    CategoryFilter("Lesbian", "lesbian"),
    CategoryFilter("MILF", "milf"),
    CategoryFilter("Teen", "teen"),
    CategoryFilter("Threesome", "threesome"),
    CategoryFilter("VR", "vr"),
    CategoryFilter("4K", "4k"),
    CategoryFilter("60FPS", "60fps"),
    AnimeFilter.Separator(),
    
    // Quality Filters
    AnimeFilter.Header("QUALITY FILTERS"),
    QualityFilter(),
    AnimeFilter.Separator(),
    
    // Duration Filters
    AnimeFilter.Header("DURATION FILTERS"),
    DurationFilter(),
    AnimeFilter.Separator(),
    
    // Date Filters
    AnimeFilter.Header("DATE FILTERS"),
    DateFilter(),
    AnimeFilter.Separator(),
    
    // Advanced Filters
    AnimeFilter.Header("ADVANCED FILTERS"),
    HDOnlyFilter(),
    VRFilter(),
    PremiumFilter(),
    AnimeFilter.Separator(),
    
    // Actor/Performer Filter
    AnimeFilter.Header("ACTOR/PERFORMER FILTER"),
    ActorFilter("Search by Actor Name"),
    AnimeFilter.Separator(),
    
    // Content Type Filters
    AnimeFilter.Header("CONTENT TYPE"),
    ProfessionalFilter(),
    HomemadeFilter(),
    AnimeFilter.Separator(),
    
    // Special Features
    AnimeFilter.Header("SPECIAL FEATURES"),
    StorylineFilter(),
    SubtitlesFilter(),
    AnimeFilter.Separator(),
)

// ==================== FILTER CLASSES ====================

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>
) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class SortFilter : UriPartFilter(
    "Sort By",
    arrayOf(
        Pair("Most Viewed", "most-viewed"),
        Pair("Top Rated", "top-rated"),
        Pair("Most Recent", "latest"),
        Pair("Longest", "longest"),
        Pair("Most Favorited", "most-favorited"),
        Pair("Most Commented", "most-commented"),
        Pair("Trending", "trending"),
        Pair("Recommended", "recommended")
    )
)

class CategoryFilter(
    name: String,
    private val slug: String
) : AnimeFilter.CheckBox(name) {
    fun toUriPart() = slug
}

class QualityFilter : UriPartFilter(
    "Video Quality",
    arrayOf(
        Pair("Any Quality", ""),
        Pair("4K Ultra HD", "4k"),
        Pair("Full HD (1080p)", "1080p"),
        Pair("HD (720p)", "720p"),
        Pair("SD (480p)", "480p"),
        Pair("Low (360p)", "360p"),
        Pair("Mobile (240p)", "240p")
    )
)

class DurationFilter : UriPartFilter(
    "Duration",
    arrayOf(
        Pair("Any Duration", ""),
        Pair("Short (0-10 min)", "0-10"),
        Pair("Medium (10-20 min)", "10-20"),
        Pair("Long (20-30 min)", "20-30"),
        Pair("Very Long (30+ min)", "30+"),
        Pair("Feature (40+ min)", "40+")
    )
)

class DateFilter : UriPartFilter(
    "Upload Date",
    arrayOf(
        Pair("Any Time", ""),
        Pair("Last 24 Hours", "1"),
        Pair("Last 7 Days", "7"),
        Pair("Last 30 Days", "30"),
        Pair("Last 90 Days", "90"),
        Pair("Last 365 Days", "365"),
        Pair("This Year", "year"),
        Pair("All Time", "all")
    )
)

class HDOnlyFilter : AnimeFilter.CheckBox("HD Only")
class VRFilter : AnimeFilter.CheckBox("VR Only")
class PremiumFilter : AnimeFilter.CheckBox("Premium Content")
class ActorFilter(name: String) : AnimeFilter.Text(name)
class ProfessionalFilter : AnimeFilter.CheckBox("Professional")
class HomemadeFilter : AnimeFilter.CheckBox("Homemade/Amateur")
class StorylineFilter : AnimeFilter.CheckBox("Has Storyline")
class SubtitlesFilter : AnimeFilter.CheckBox("Has Subtitles")
