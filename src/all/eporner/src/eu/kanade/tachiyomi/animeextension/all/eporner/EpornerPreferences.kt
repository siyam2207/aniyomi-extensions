package eu.kanade.tachiyomi.animeextension.all.eporner

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen

internal object EpornerPreferences {

    const val PREF_QUALITY_KEY = "preferred_quality"
    const val PREF_QUALITY_DEFAULT = "720p"
    val QUALITY_LIST = arrayOf("best", "1080p", "720p", "480p", "360p")

    const val PREF_SORT_KEY = "default_sort"
    const val PREF_SORT_DEFAULT = "top-weekly"
    val SORT_LIST = arrayOf("latest", "top-weekly", "top-monthly", "most-viewed", "top-rated")

    fun setup(screen: PreferenceScreen, preferences: SharedPreferences) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SORT_KEY
            title = "Default sort order"
            entries = SORT_LIST
            entryValues = SORT_LIST
            setDefaultValue(PREF_SORT_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }
}
