// In your main Eporner.kt, update the videoListParse method:
override fun videoListParse(response: Response): List<Video> {
    return try {
        val embedUrl = response.request.url.toString()
        Log.d(tag, "Extracting from embed URL: $embedUrl")
        
        // Try the enhanced extractor
        val videos = epornerExtractor.videosFromEmbed(embedUrl)
        
        if (videos.isEmpty()) {
            Log.d(tag, "Extractor returned empty, trying direct HLS discovery...")
            
            // Fallback: Try to find HLS URL directly in the response
            val html = response.body?.string() ?: return emptyList()
            val directHls = findHlsUrlDirectly(html, embedUrl)
            if (directHls != null) {
                return listOf(Video(directHls, "Eporner HLS", directHls))
            }
        }
        
        videos
    } catch (e: Exception) {
        Log.e(tag, "Video extraction error", e)
        emptyList()
    }
}

private fun findHlsUrlDirectly(html: String, refererUrl: String): String? {
    // Look for HLS URLs in the HTML
    val patterns = listOf(
        Pattern.compile("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
        Pattern.compile("""src=["']([^"']+\.m3u8[^"']*)["']"""),
        Pattern.compile("""hls["']?:\s*["']([^"']+)["']""")
    )
    
    for (pattern in patterns) {
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            val url = matcher.group(1)
            if (url.contains("eporner.com") && url.contains(".m3u8")) {
                return url
            }
        }
    }
    return null
}
