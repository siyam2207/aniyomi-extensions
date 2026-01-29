package eu.kanade.tachiyomi.animeextension.en.eporner

object EpornerApi {

    const val BASE_API = "https://www.eporner.com/api/v2"

    fun popular(page: Int): String =
        "$BASE_API/video/search/?query=&per_page=30&page=$page&order=top-weekly&thumbsize=medium"

    fun latest(page: Int): String =
        "$BASE_API/video/search/?query=&per_page=30&page=$page&order=latest&thumbsize=medium"

    fun search(query: String, page: Int): String =
        "$BASE_API/video/search/?query=$query&per_page=30&page=$page&thumbsize=medium"

    fun video(id: String): String =
        "$BASE_API/video/get?id=$id"
}
