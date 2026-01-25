// PreviewExtractor.kt
package eu.kanade.tachiyomi.extension.all.eporner

class VideoPreviewExtractor {
    companion object {
        suspend fun extractPreviews(videoId: String): List<String> {
            // Method 1: From stored thumbnails in SAnime
            // Method 2: Direct API call for fresh data
            val apiUrl = "https://www.eporner.com/api/v2/video/id/"
                .toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("id", videoId)
                .addQueryParameter("thumbsize", "big")
                .build()
            
            val response = client.newCall(GET(apiUrl.toString())).await()
            val apiResponse = jsonParser.decodeFromString<ApiVideoDetailResponse>(
                response.body.string())
            
            // Extract and order thumbnails
            return apiResponse.video.thumbs
                .sortedBy { it.src.substringAfterLast("_").substringBefore(".").toIntOrNull() }
                .map { it.src }
                .distinct()
        }
        
        fun generatePreviewUrls(thumbUrls: List<String>): List<String> {
            // Convert thumbnail URLs to potential preview URLs
            // Some thumbnails might be frames from the video
            return thumbUrls.map { url ->
                // Try to extract frame number and construct preview
                val frameMatch = Regex("""(\d+)_\d+\.jpg""").find(url)
                frameMatch?.groupValues?.get(1)?.let { frameNumber ->
                    // Construct preview URL pattern (site-specific)
                    url.replace(
                        "thumbs/static4", 
                        "preview/$frameNumber"
                    ).replace(".jpg", ".mp4")
                } ?: url.replace("/thumbs/", "/preview/").replace(".jpg", ".gif")
            }.filterNotNull()
        }
    }
}
