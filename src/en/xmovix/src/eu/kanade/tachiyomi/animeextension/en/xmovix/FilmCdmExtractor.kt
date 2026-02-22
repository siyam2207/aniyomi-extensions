package eu.kanade.tachiyomi.animeextension.en.xmovix

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import okhttp3.Headers
import okhttp3.OkHttpClient

class FilmCdmExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        // UniversalExtractor handles JavaScript and returns all extracted videos
        return UniversalExtractor(client).videosFromUrl(url, headers, prefix = prefix)
    }
}
