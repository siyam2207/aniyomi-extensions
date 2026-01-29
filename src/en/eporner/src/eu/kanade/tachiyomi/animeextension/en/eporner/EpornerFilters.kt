package eu.kanade.tachiyomi.animeextension.en.eporner

import android.content.Context
import android.preference.PreferenceManager
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object EpornerFilters {

    // Categories for filter
    private val categories = arrayOf(
        "All",
        "Amateur",
        "Anal",
        "Asian",
        "Big Tits",
        "Blowjob",
        "MILF",
        "Teen",
    )

    // Get filters from SharedPreferences or default values
    fun getFilters(context: Context): AnimeFilterList {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedCategory = prefs.getString("eporner_category", "All") ?: "All"
        val savedMinDuration = prefs.getInt("eporner_min_duration", 0)
        val savedMaxDuration = prefs.getInt("eporner_max_duration", 120)

        val categoryFilter = AnimeFilter.Select<String>(
            "Category",
            categories,
            categories.indexOf(savedCategory),
        )
        val durationFilter = AnimeFilter.Range(
            "Duration (min)",
            savedMinDuration,
            savedMaxDuration,
        )

        return AnimeFilterList(
            categoryFilter,
            durationFilter,
        )
    }

    // Save filters to SharedPreferences
    fun saveFilters(context: Context, filters: AnimeFilterList) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context).edit()
        val category = (filters[0] as AnimeFilter.Select<String>).state
        val duration = filters[1] as AnimeFilter.Range

        prefs.putString("eporner_category", category)
        prefs.putInt("eporner_min_duration", duration.min)
        prefs.putInt("eporner_max_duration", duration.max)
        prefs.apply()
    }

    // Apply filters to API request URL
    fun applyFilters(url: StringBuilder, filters: AnimeFilterList) {
        val category = (filters[0] as AnimeFilter.Select<String>).state
        val duration = filters[1] as AnimeFilter.Range

        if (category != "All") {
            url.append("&category=${category.lowercase()}")
        }
        if (duration.min > 0) {
            url.append("&min_duration=${duration.min}")
        }
        if (duration.max < 120) {
            url.append("&max_duration=${duration.max}")
        }
    }
}
