class Eporner : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Eporner"
    override val baseUrl = "https://www.eporner.com"
    override val lang = "all"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }
    private val tag = "EpornerExtension"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "application/json, text/html, */*")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Origin", baseUrl)
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

    // Popular / Latest
    override fun popularAnimeRequest(page: Int): Request =
        EpornerApi.popularAnimeRequest(page, headers, baseUrl)

    override fun popularAnimeParse(response: Response) =
        EpornerApi.popularAnimeParse(response, json, tag)

    override fun latestUpdatesRequest(page: Int): Request =
        EpornerApi.latestUpdatesRequest(page, headers, baseUrl)

    override fun latestUpdatesParse(response: Response) =
        popularAnimeParse(response)

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        EpornerApi.searchAnimeRequest(page, query, filters, headers, baseUrl)

    override fun searchAnimeParse(response: Response) =
        popularAnimeParse(response)

    // Anime Details
    override fun animeDetailsRequest(anime: SAnime): Request =
        EpornerApi.animeDetailsRequest(anime, headers, baseUrl)

    override fun animeDetailsParse(response: Response): SAnime =
        try {
            EpornerApi.animeDetailsParse(response, json)
        } catch (e: Exception) {
            Log.w(tag, "API details failed: ${e.message}")
            EpornerApi.htmlAnimeDetailsParse(response)
        }

    // Episodes
    override fun episodeListRequest(anime: SAnime): Request =
        GET(anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> =
        listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.toString()
            }
        )

    // Videos
    override fun videoListRequest(episode: SEpisode): Request =
        GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> =
        EpornerApi.videoListParse(response, client, headers)

    override fun videoUrlParse(response: Response): String =
        videoListParse(response).firstOrNull()?.videoUrl ?: ""

    // Filters
    override fun getFilterList() = EpornerFilters.filterList

    // Preferences required by ConfigurableAnimeSource
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // No custom preferences yet
    }
}
