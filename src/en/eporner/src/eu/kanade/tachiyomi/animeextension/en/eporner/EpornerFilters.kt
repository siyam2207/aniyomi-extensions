package eu.kanade.tachiyomi.animeextension.en.eporner

import android.content.Context
import android.preference.PreferenceManager
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object EpornerFilters {

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

    fun getFilters(context: Context): AnimeFilterList {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val savedCategory = prefs.getString("eporner_category", "All") ?: "All"
        val savedMinDuration = prefs.getInt("eporner_min_duration", 0)
        val savedMaxDuration = prefs.getInt("eporner_max_duration", 120)

        val categoryFilter = AnimeFilter.Select(
            "Category",
            categories,
            categories.indexOf(savedCategory),
        )

        val durationFilter = AnimeFilter.Range(
            "Duration (minutes)",
            savedMinDuration,
            savedMaxDuration,
        )

        return AnimeFilterList(
            categoryFilter,
            durationFilter,
        )
    }

    fun saveFilters(context: Context, filters: AnimeFilterList) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context).edit()

        val category = (filters[0] as AnimeFilter.Select<String>).state
        val duration = filters[1] as AnimeFilter.Range

        prefs.putString("eporner_category", category)
        prefs.putInt("eporner_min_duration", duration.min().toInt())
        prefs.putInt("eporner_max_duration", duration.max().toInt())
        prefs.apply()
    }

    fun applyFilters(url: StringBuilder, filters: AnimeFilterList) {
        val category = (filters[0] as AnimeFilter.Select<String>).state
        val duration = filters[1] as AnimeFilter.Range

        val min = duration.min()
        val max = duration.max()

        if (category != "All") {
            url.append("&category=${category.lowercase()}")
        }
        if (min > 0) {
            url.append("&min_duration=${min.toInt()}")
        }
        if (max < 120) {
            url.append("&max_duration=${max.toInt()}")
        }
    }
}
