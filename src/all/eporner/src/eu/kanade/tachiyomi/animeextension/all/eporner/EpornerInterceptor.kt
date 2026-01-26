package eu.kanade.tachiyomi.animeextension.all.eporner

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Rotating User-Agent interceptor to prevent bot detection
 */
class RotatingUserAgentInterceptor : Interceptor {
    private val userAgents = listOf(
        // Chrome on Windows (updated)
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        // Safari on macOS
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        // Firefox on Windows
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
        // Edge on Windows
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 Edg/123.0.0.0",
        // Chrome on Android
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
    )

    private var currentIndex = 0

    private fun getNextUserAgent(): String {
        synchronized(this) {
            val agent = userAgents[currentIndex]
            currentIndex = (currentIndex + 1) % userAgents.size
            return agent
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Don't override if User-Agent already exists from headersBuilder
        val userAgentHeader = originalRequest.header("User-Agent")
        val newUserAgent = if (userAgentHeader.isNullOrEmpty()) {
            getNextUserAgent()
        } else {
            userAgentHeader
        }

        val newRequest = originalRequest.newBuilder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", newUserAgent)
            .addHeader("Accept", "application/json, text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Accept-Encoding", "gzip, deflate, br")
            .addHeader("Referer", "https://www.eporner.com/")
            .addHeader("Origin", "https://www.eporner.com")
            .addHeader("DNT", "1")
            .addHeader("Connection", "keep-alive")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-origin")
            .build()

        return chain.proceed(newRequest)
    }
}

/**
 * Rate limiting and retry interceptor for 403 errors
 */
class EpornerRateLimitInterceptor : Interceptor {
    private data class RateLimitInfo(
        val timestamp: Long,
        var requestCount: Int = 1,
    )

    private val rateLimitMap = mutableMapOf<String, RateLimitInfo>()
    private val lock = Any()

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 25
        private const val TIME_WINDOW_MS = TimeUnit.MINUTES.toMillis(1)
        private const val RETRY_DELAY_MS = 2000L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val info = rateLimitMap[host]

            if (info != null && now - info.timestamp < TIME_WINDOW_MS) {
                if (info.requestCount >= MAX_REQUESTS_PER_MINUTE) {
                    // Calculate wait time
                    val timeToWait = TIME_WINDOW_MS - (now - info.timestamp)
                    if (timeToWait > 0) {
                        Thread.sleep(timeToWait)
                        info.requestCount = 1
                        info.timestamp = System.currentTimeMillis()
                    }
                } else {
                    info.requestCount++
                }
            } else {
                rateLimitMap[host] = RateLimitInfo(now)
            }
        }

        // Execute request with retry logic
        var response: Response? = null
        var lastException: Exception? = null

        for (attempt in 1..3) {
            try {
                response = chain.proceed(chain.request())

                when (response.code) {
                    403 -> {
                        response.close()
                        // Clear any cached cookies or session data
                        val cookies = response.headers("Set-Cookie")
                        if (cookies.isNotEmpty()) {
                            // Log cookie changes for debugging
                            println("403 received, cookies present: ${cookies.size}")
                        }

                        if (attempt < 3) {
                            Thread.sleep(RETRY_DELAY_MS * attempt)
                            continue
                        } else {
                            throw Exception("HTTP 403 Forbidden after $attempt attempts")
                        }
                    }
                    429 -> { // Too Many Requests
                        response.close()
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?: (RETRY_DELAY_MS * attempt)
                        Thread.sleep(retryAfter)
                        continue
                    }
                    200, 201 -> {
                        return response
                    }
                    else -> {
                        response.close()
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < 3) {
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                }
            }
        }

        response?.close()
        throw lastException ?: Exception("Failed to complete request after 3 attempts")
    }
}
