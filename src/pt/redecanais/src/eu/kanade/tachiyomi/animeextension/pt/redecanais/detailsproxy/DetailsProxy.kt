package eu.kanade.tachiyomi.animeextension.pt.redecanais.detailsproxy

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.pt.redecanais.destroyHeadless
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DetailsProxy(
    baseUrl: String,
    private val userAgentProvider: () -> String,
) : Interceptor {

    private val baseHost = baseUrl.toHttpUrl().host
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val captureDetailsScript by lazy {
        javaClass.getResource("/assets/capture-details.js")?.readText()
            ?: throw Exception("error_capture_details_script_not_found")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.shouldProxyDetails(baseHost)) return chain.proceed(request)

        return loadDetailsHtml(request.url.toString())?.toResponse(
            request,
            chain.connection()?.protocol() ?: Protocol.HTTP_1_1,
        ) ?: throw IOException("WebView Proxy Timeout")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun loadDetailsHtml(url: String): DetailsProxyResult? {
        val latch = CountDownLatch(1)
        val bridge = DetailsBridge(latch)
        val webViewRef = AtomicReference<WebView?>()

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

            view.addJavascriptInterface(bridge, DETAILS_BRIDGE_INTERFACE)
            view.webViewClient = DetailsWebViewClient(bridge)
            view.loadUrl(url)

            DETAILS_CAPTURE_DELAYS.forEach { delay -> scheduleCapture(view, bridge, delay) }
        }

        latch.await(DETAILS_PROXY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return bridge.html.also {
            handler.post {
                webViewRef.getAndSet(null)?.destroyHeadless(DETAILS_BRIDGE_INTERFACE)
            }
        }
    }

    private fun scheduleCapture(
        view: WebView,
        bridge: DetailsBridge,
        delayMillis: Long = 0L,
    ) {
        handler.postDelayed(
            {
                if (!bridge.hasFinished()) view.evaluateJavascript(captureDetailsScript, null)
            },
            delayMillis,
        )
    }

    private inner class DetailsWebViewClient(
        private val bridge: DetailsBridge,
    ) : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = request.blockedDetailsResponse(baseHost) ?: super.shouldInterceptRequest(view, request)

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            scheduleCapture(view, bridge)
        }
    }
}
