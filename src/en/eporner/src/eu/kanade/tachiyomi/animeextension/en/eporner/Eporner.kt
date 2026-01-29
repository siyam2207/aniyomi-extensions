package eu.kanade.tachiyomi.animeextension.en.eporner

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class Eporner : AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("User-Agent", AnimeSource.USER_AGENT)

    // ================= POPULAR =================

    override fun popularAnimeRequest(page: Int): Request =
        GET(EpornerApi.popular(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<VideoListResponse>()
        return AnimesPage(
            data.videos.map { it.toAnime() },
            data.page < data.pages,
        )
    }

    // ================= LATEST =================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(EpornerApi.latest(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ================= SEARCH =================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList,
    ): Request =
        GET(EpornerApi.search(query, page), headers)

    override fun searchAnimeParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // ================= DETAILS =================

    override fun animeDetailsParse(response: Response): SAnime =
        SAnime.create().apply { status = SAnime.COMPLETED }

    // ================= EPISODES =================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Video"
            },
        )

    // ================= VIDEOS =================

    override fun videoListRequest(episode: SEpisode): Request =
        GET(EpornerApi.video(episode.url), headers)

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseAs<VideoDetailResponse>()
        val video = data.video

        val videos = mutableListOf<Video>()

        video.hls?.takeIf { it.isNotBlank() }?.let {
            videos.add(Video(it, "HLS", it, headers))
        }

        video.mp4.forEach {
            videos.add(Video(it.url, it.quality, it.url, headers))
        }

        return videos.sortedByDescending {
            it.quality.filter(Char::isDigit).toIntOrNull() ?: 0
        }
    }
}
