package eu.kanade.tachiyomi.animeextension.all.eporner

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class EpornerFactory : AnimeSourceFactory {

    override fun createSources(): List<AnimeSource> =
        listOf(
            Eporner(),
        )
}
