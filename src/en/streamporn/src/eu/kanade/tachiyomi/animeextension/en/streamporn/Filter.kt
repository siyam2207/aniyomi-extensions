package eu.kanade.tachiyomi.animeextension.en.streamporn

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Section filter – allows browsing different parts of the site.
 */
open class SectionFilter : AnimeFilter.Select<String>(
    "Section",
    arrayOf(
        "All",
        "Movies",
        "Most Viewed",
        "Most Rating",
        "Studios",
    ),
) {
    fun getPath(): String = when (state) {
        1 -> "movies"
        2 -> "most-viewed"
        3 -> "most-rating"
        4 -> "studios"
        else -> ""
    }
}

/**
 * Popular Studio dropdown – top 50 studios by number of movies.
 */
open class PopularStudioFilter : AnimeFilter.Select<String>(
    "Popular Studio",
    listOf(
        "None",
        "N/A",
        "Elegant Angel",
        "New Sensations",
        "Private",
        "Evil Angel",
        "Digital Sin",
        "Adam & Eve",
        "Bang! Originals",
        "Reality Kings",
        "EAGLE",
        "Lethal Hardcore",
        "Brazzers",
        "Girlfriends Films",
        "Devil's Film",
        "West Coast Productions",
        "Wicked Pictures",
        "21 Sextury Video",
        "MMV",
        "Kink Clips",
        "White Ghetto",
        "Hustler",
        "Marc Dorcel",
        "#LETSDOEIT",
        "Penthouse",
        "Digital Playground",
        "Naughty America",
        "Teen Erotica Clips",
        "Cento X Cento",
        "Porn Pros",
        "Jules Jordan Video",
        "STRIX",
        "Mile High Xtreme",
        "Bang Bros Productions",
        "Fun Picture",
        "Grooby Clips",
        "Team Skeet",
        "German Amateur Girls",
        "Erotic Planet",
        "Zero Tolerance",
        "Magma Film",
        "Jeff's Models Clips",
        "SexMex",
        "Eye Candy",
        "Dream Tranny Clips",
        "Grooby",
        "Pink Visual",
        "be.me.fi",
        "MAX-A",
        "DDF Network",
        "Deutschland Porno",
    ).toTypedArray(),
) {
    private val slugMap = mapOf(
        "None" to null,
        "N/A" to "n-a",
        "Elegant Angel" to "elegant-angel",
        "New Sensations" to "new-sensations",
        "Private" to "private",
        "Evil Angel" to "evil-angel",
        "Digital Sin" to "digital-sin",
        "Adam & Eve" to "adam-eve",
        "Bang! Originals" to "bang-originals",
        "Reality Kings" to "reality-kings",
        "EAGLE" to "eagle",
        "Lethal Hardcore" to "lethal-hardcore",
        "Brazzers" to "brazzers",
        "Girlfriends Films" to "girlfriends-films",
        "Devil's Film" to "devils-film",
        "West Coast Productions" to "west-coast-productions",
        "Wicked Pictures" to "wicked-pictures",
        "21 Sextury Video" to "21-sextury-video",
        "MMV" to "mmv",
        "Kink Clips" to "kink-clips",
        "White Ghetto" to "white-ghetto",
        "Hustler" to "hustler",
        "Marc Dorcel" to "marc-dorcel",
        "#LETSDOEIT" to "letsdoeit",
        "Penthouse" to "penthouse",
        "Digital Playground" to "digital-playground",
        "Naughty America" to "naughty-america",
        "Teen Erotica Clips" to "teen-erotica-clips",
        "Cento X Cento" to "cento-x-cento",
        "Porn Pros" to "porn-pros",
        "Jules Jordan Video" to "jules-jordan-video",
        "STRIX" to "strix",
        "Mile High Xtreme" to "mile-high-xtreme",
        "Bang Bros Productions" to "bang-bros-productions",
        "Fun Picture" to "fun-picture",
        "Grooby Clips" to "grooby-clips",
        "Team Skeet" to "team-skeet",
        "German Amateur Girls" to "german-amateur-girls",
        "Erotic Planet" to "erotic-planet",
        "Zero Tolerance" to "zero-tolerance",
        "Magma Film" to "magma-film",
        "Jeff's Models Clips" to "jeffs-models-clips",
        "SexMex" to "sexmex",
        "Eye Candy" to "eye-candy",
        "Dream Tranny Clips" to "dream-tranny-clips",
        "Grooby" to "grooby",
        "Pink Visual" to "pink-visual",
        "be.me.fi" to "be-me-fi",
        "MAX-A" to "max-a",
        "DDF Network" to "ddf-network",
        "Deutschland Porno" to "deutschland-porno",
    )

    fun getSlug(): String? = slugMap[values[state]]
}

/**
 * Studio text filter – enter the studio name (e.g., "Purple Bitch").
 * The extension will convert it to the URL slug automatically.
 */
open class StudioTextFilter : AnimeFilter.Text("Studio name (e.g., Purple Bitch)")

/**
 * Returns the complete filter list.
 */
fun getFilterList(): AnimeFilterList {
    return AnimeFilterList(
        SectionFilter(),
        PopularStudioFilter(),
        StudioTextFilter(),
    )
}
