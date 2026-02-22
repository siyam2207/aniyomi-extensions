package eu.kanade.tachiyomi.animeextension.en.xmovix

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FilmCdmExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var masterUrl: String? = null

        // Prepare headers for WebView (same as original request)
        val webViewHeaders = headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        handler.post {
            val newView = WebView(context)
            webView = newView
            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }

            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val requestUrl = request.url.toString()
                    // Look for master.m3u8 (as seen in Python output)
                    if (requestUrl.contains(".m3u8") && requestUrl.contains("master")) {
                        masterUrl = requestUrl
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            newView.loadUrl(url, webViewHeaders)
        }

        // Wait up to 30 seconds for the master playlist to appear
        latch.await(30, TimeUnit.SECONDS)

        // Retrieve cookies for the master URL domain
        val cookies = masterUrl?.let { CookieManager.getInstance().getCookie(it) } ?: ""

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        if (masterUrl.isNullOrBlank()) return emptyList()

        // Build headers with cookies, referer, and origin (as seen in Python script)
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", "https://filmcdm.top")
            .apply {
                if (cookies.isNotBlank()) {
                    set("Cookie", cookies)
                }
            }
            .build()

        val playlistUtils = PlaylistUtils(client, videoHeaders)

        // Ensure headers are used for all requests (master playlist and segments)
        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = url,
            masterHeadersGen = { _, _ -> videoHeaders },
            videoHeadersGen = { _, _, _ -> videoHeaders },
            videoNameGen = { quality -> "${prefix}FilmCdm - $quality" },
        )
    }
}
