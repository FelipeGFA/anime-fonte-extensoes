package eu.kanade.tachiyomi.animeextension.pt.redecanais.searchthumbproxy

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.pt.redecanais.destroyHeadless
import eu.kanade.tachiyomi.animeextension.pt.redecanais.detailsproxy.blockedDetailsResponse
import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.toRedeCanaisHost
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SearchThumbProxy(
    private val baseUrl: String,
    private val userAgentProvider: () -> String,
) : Interceptor {

    private val baseHost = baseUrl.toHttpUrl().host
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val cache = object : LinkedHashMap<String, SearchThumbResult>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchThumbResult>?): Boolean = size > MAX_ENTRIES
    }
    private val resolverSlots = Semaphore(MAX_CONCURRENT_RESOLVERS)
    private val captureSearchThumbScript by lazy {
        javaClass.getResource("/assets/capture-search-thumb.js")?.readText()
            ?: throw Exception("error_capture_search_thumb_script_not_found")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || request.url.host != baseHost || request.url.encodedPath != SEARCH_THUMB_PATH) {
            return chain.proceed(request)
        }

        val detailsUrl = request.url.queryParameter(SEARCH_THUMB_URL_PARAM)
            ?.absoluteRedeCanaisUrl()
            ?.toRedeCanaisHost(baseHost)
            ?: throw IOException("Missing search thumbnail url")
        val thumb = cachedThumb(detailsUrl) ?: resolveThumb(detailsUrl)?.also { putThumb(detailsUrl, it) }
            ?: throw IOException("Search thumbnail not found")

        return thumb.toResponse(request, chain.connection()?.protocol() ?: Protocol.HTTP_1_1)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun resolveThumb(detailsUrl: String): SearchThumbResult? {
        resolverSlots.acquire()
        val latch = CountDownLatch(1)
        val bridge = SearchThumbBridge(latch)
        val webViewRef = AtomicReference<WebView?>()

        try {
            handler.post {
                val view = WebView(context)
                webViewRef.set(view)

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    userAgentString = userAgentProvider()
                }

                view.addJavascriptInterface(bridge, SEARCH_THUMB_BRIDGE_INTERFACE)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = request.blockedDetailsResponse(baseHost) ?: super.shouldInterceptRequest(view, request)

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        captureThumb(view, bridge)
                    }
                }
                view.loadUrl(detailsUrl)
                handler.postDelayed({ captureThumb(view, bridge) }, SEARCH_THUMB_CAPTURE_DELAY_MS)
            }

            latch.await(SEARCH_THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return bridge.result.also {
                bridge.finish()
                handler.post {
                    webViewRef.getAndSet(null)?.destroyHeadless(SEARCH_THUMB_BRIDGE_INTERFACE)
                }
            }
        } finally {
            resolverSlots.release()
        }
    }

    private fun captureThumb(view: WebView, bridge: SearchThumbBridge) {
        if (!bridge.hasFinished()) view.evaluateJavascript(captureSearchThumbScript, null)
    }

    private fun String.absoluteRedeCanaisUrl(): String = when {
        startsWith("http", ignoreCase = true) -> this
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }

    @Synchronized
    private fun cachedThumb(detailsUrl: String): SearchThumbResult? = cache[detailsUrl]

    @Synchronized
    private fun putThumb(detailsUrl: String, result: SearchThumbResult) {
        cache[detailsUrl] = result
    }

    private class SearchThumbBridge(
        private val latch: CountDownLatch,
    ) {
        @Volatile
        var result: SearchThumbResult? = null
            private set

        @Volatile
        private var finished = false

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passThumbImage(contentType: String, base64: String) {
            if (finished) return
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            result = SearchThumbResult(
                contentType = contentType.takeIf { it.startsWith("image/") } ?: "image/jpeg",
                bytes = bytes,
            ).takeIf { bytes.isNotEmpty() }
            finished = true
            latch.countDown()
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun fail() {
            finish()
        }

        fun hasFinished(): Boolean = finished

        fun finish() {
            if (finished) return
            finished = true
            latch.countDown()
        }
    }

    private class SearchThumbResult(
        private val contentType: String,
        private val bytes: ByteArray,
    ) {
        fun toResponse(request: Request, protocol: Protocol): Response = Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(200)
            .message("OK")
            .headers(
                Headers.headersOf(
                    "Content-Type",
                    contentType,
                    "Content-Length",
                    bytes.size.toString(),
                ),
            )
            .body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    companion object {
        const val SEARCH_THUMB_PATH = "/__rc_thumb"
        const val SEARCH_THUMB_URL_PARAM = "url"
        private const val SEARCH_THUMB_BRIDGE_INTERFACE = "SearchThumbProxy"
        private const val SEARCH_THUMB_TIMEOUT_SECONDS = 25L
        private const val SEARCH_THUMB_CAPTURE_DELAY_MS = 300L
        private const val MAX_CONCURRENT_RESOLVERS = 2
        private const val MAX_ENTRIES = 160
    }
}
