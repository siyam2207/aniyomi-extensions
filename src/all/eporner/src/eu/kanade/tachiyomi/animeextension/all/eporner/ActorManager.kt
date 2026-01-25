// ActorManager.kt
package eu.kanade.tachiyomi.extension.all.eporner

import org.jsoup.Jsoup

class ActorManager(private val client: OkHttpClient) {
    private val actorCache = mutableMapOf<String, List<String>>()
    
    suspend fun extractActorsFromVideo(videoId: String): List<String> {
        // Check cache first
        actorCache[videoId]?.let { return it }
        
        // Method 1: Parse video page for actor links
        val videoUrl = "https://www.eporner.com/hd-porn/$videoId/"
        val response = client.newCall(GET(videoUrl)).await()
        val document = response.asJsoup()
        
        val actors = document.select("a[href^=/pornstar/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        
        // Method 2: Fallback to keywords parsing
        if (actors.isEmpty()) {
            val apiUrl = "https://www.eporner.com/api/v2/video/id/"
                .toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("id", videoId)
                .build()
            
            val apiResponse = client.newCall(GET(apiUrl.toString())).await()
            val videoDetail = jsonParser.decodeFromString<ApiVideoDetailResponse>(
                apiResponse.body.string())
            
            val keywords = videoDetail.video.keywords.split(", ")
            actors = keywords.filter { keyword ->
                // Heuristic: Actor names usually don't contain certain words
                !keyword.contains("porn", ignoreCase = true) &&
                !keyword.contains("sex", ignoreCase = true) &&
                !keyword.contains("hd", ignoreCase = true) &&
                keyword.length in 3..25 &&
                keyword[0].isUpperCase()
            }.distinct()
        }
        
        actorCache[videoId] = actors
        return actors
    }
    
    suspend fun getAllActors(page: Int = 1): List<Actor> {
        val url = "https://www.eporner.com/pornstar-list/$page/"
        val response = client.newCall(GET(url)).await()
        val document = response.asJsoup()
        
        return document.select(".mbprofile").mapNotNull { element ->
            val link = element.select("a[href^=/pornstar/]").first()
            val img = element.select("img").first()
            val count = element.select(".videos-count").text()
                .removeSurrounding("(", ")").toIntOrNull() ?: 0
            
            if (link != null && img != null) {
                Actor(
                    name = link.text(),
                    url = link.attr("href"),
                    thumbnail = img.attr("src"),
                    videoCount = count
                )
            } else null
        }
    }
    
    fun getActorVideosQuery(actorName: String): String {
        // Convert actor name to search query
        return actorName.replace(" ", "-").lowercase()
    }
}
