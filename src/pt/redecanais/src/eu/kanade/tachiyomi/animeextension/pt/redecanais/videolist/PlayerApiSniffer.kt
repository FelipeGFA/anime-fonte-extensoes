package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.pt.redecanais.destroyHeadless
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PlayerApiSniffer(
    private val baseUrl: String,
    private val userAgentProvider: () -> String,
) {

    private val baseHost = baseUrl.toHttpUrl().host
    private val context: Application by injectLazy()
    private val tag by lazy { javaClass.simpleName }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val capturePlayerApiScript by lazy {
        javaClass.getResource("/assets/capture-player-api.js")?.readText()
            ?: throw Exception("error_capture_player_api_script_not_found")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun sniffAll(pageUrls: List<String>): Map<String, Result> {
        if (pageUrls.isEmpty()) return emptyMap()

        Log.d(tag, "sniffAll start count=${pageUrls.size} ${pageUrls.mapIndexed { index, url -> "$index=${url.shortLogUrl()}" }.joinToString()}")

        val latch = CountDownLatch(1)
        val results = linkedMapOf<String, Result>()
        val webViewRef = AtomicReference<WebView?>()

        handler.post {
            CookieManager.getInstance().setAcceptCookie(true)

            val view = WebView(context)
            webViewRef.set(view)
            var index = 0
            var token = 0
            var currentPageUrl = ""
            lateinit var bridge: PlayerBridge

            fun loadCurrent() {
                token++
                bridge.reset(token)
                currentPageUrl = pageUrls[index]
                Log.d(tag, "load index=$index token=$token url=${currentPageUrl.shortLogUrl()}")
                view.loadUrl(currentPageUrl, mapOf("Referer" to "$baseUrl/"))
            }

            bridge = PlayerBridge(baseUrl, baseHost) { result ->
                handler.post {
                    Log.d(tag, "result index=$index token=$token page=${pageUrls[index].shortLogUrl()} stream=${result?.url?.shortLogUrl().orEmpty()} referer=${result?.referer?.shortLogUrl().orEmpty()}")
                    result?.let { results[pageUrls[index]] = it }
                    index++
                    if (index >= pageUrls.size) {
                        Log.d(tag, "sniffAll complete results=${results.size}")
                        latch.countDown()
                    } else {
                        loadCurrent()
                    }
                }
            }

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
                userAgentString = userAgentProvider()
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

            view.addJavascriptInterface(bridge, PLAYER_BRIDGE_INTERFACE)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = request.blockedPlayerResponse(baseHost) ?: super.shouldInterceptRequest(view, request)

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(tag, "pageFinished token=$token expected=${currentPageUrl.shortLogUrl()} url=${url.orEmpty().shortLogUrl()}")
                    if (!url.orEmpty().sameUrlAs(currentPageUrl)) {
                        Log.d(tag, "pageFinished ignored token=$token expected=${currentPageUrl.shortLogUrl()} url=${url.orEmpty().shortLogUrl()}")
                        return
                    }
                    capturePlayerIframe(view, bridge, token)
                }
            }

            loadCurrent()
        }

        val completed = latch.await(PLAYER_TIMEOUT_SECONDS * pageUrls.size, TimeUnit.SECONDS)
        if (!completed) {
            Log.d(tag, "sniffAll timeout results=${results.size}/${pageUrls.size}")
        }

        handler.post {
            webViewRef.getAndSet(null)?.destroyHeadless(PLAYER_BRIDGE_INTERFACE)
        }

        Log.d(tag, "sniffAll return results=${results.size}")
        return results
    }

    private fun capturePlayerIframe(view: WebView, bridge: PlayerBridge, token: Int) {
        if (bridge.hasFinished()) {
            Log.d(tag, "capture skipped token=$token finished=true")
            return
        }
        Log.d(tag, "capture evaluate token=$token")
        view.evaluateJavascript("window.__rcPlayerApiSnifferToken=$token;$capturePlayerApiScript", null)
    }

    private fun String.shortLogUrl(): String {
        val url = toHttpUrlOrNull() ?: return takeLast(80)
        val query = url.encodedQuery?.let { "?${it.take(80)}" }.orEmpty()
        return "${url.host}${url.encodedPath}$query"
    }

    private fun String.sameUrlAs(other: String): Boolean {
        val left = toHttpUrlOrNull() ?: return this == other
        val right = other.toHttpUrlOrNull() ?: return this == other
        return left.host == right.host &&
            left.encodedPath == right.encodedPath &&
            left.encodedQuery == right.encodedQuery
    }

    data class Result(
        val url: String,
        val referer: String,
    )

    private companion object {
        const val PLAYER_BRIDGE_INTERFACE = "PlayerApiSniffer"
        const val PLAYER_TIMEOUT_SECONDS = 20L
    }
}
