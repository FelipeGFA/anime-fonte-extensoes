package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebViewVideoExtractor(
    private val headers: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun extract(url: String): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val webView = AtomicReference<WebView?>()

        handler.post {
            CookieManager.getInstance().setAcceptCookie(true)

            WebView(context).also { view ->
                webView.set(view)

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    userAgentString = headers["User-Agent"]
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        if (requestUrl != url && requestUrl.isVideoUrl()) {
                            result.compareAndSet(null, requestUrl)
                            latch.countDown()
                        }

                        if (request.url.encodedPath.orEmpty().isDisposableAsset()) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(VIDEO_FILE_SCRIPT) { value ->
                            value.toJsString()
                                .takeIf { it.isVideoUrl() }
                                ?.let {
                                    result.compareAndSet(null, it)
                                    latch.countDown()
                                }
                        }
                    }
                }

                view.loadUrl(url, headers.toMap())
            }
        }

        latch.await(15, TimeUnit.SECONDS)

        handler.post {
            webView.getAndSet(null)?.let { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.removeAllViews()
                view.destroy()
            }
        }

        return result.get()
    }

    private fun Headers.toMap() = buildMap {
        for ((name, value) in this@toMap) {
            put(name, value)
        }
    }

    private fun String.isDisposableAsset() = endsWith(".jpg") ||
        endsWith(".png") ||
        endsWith(".gif") ||
        endsWith(".webp") ||
        endsWith(".css")

    private fun String.isVideoUrl(): Boolean {
        val url = toHttpUrlOrNull() ?: return false
        return url.host == VIDEO_HOST ||
            (
                url.host == PROXY_HOST &&
                    url.encodedPath == PROXY_PATH &&
                    url.queryParameter(PROXY_SOURCE)?.contains(BLOGGER_HOST) == true
                )
    }

    private fun String?.toJsString(): String = this
        ?.removeSurrounding("\"")
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        .orEmpty()

    private companion object {
        const val VIDEO_HOST = "infra-dante.thatwebsite.com.br"
        const val PROXY_HOST = "traffic.thatwebsite.com.br"
        const val PROXY_PATH = "/api/proxy.php"
        const val PROXY_SOURCE = "src"
        const val BLOGGER_HOST = "www.blogger.com/video.g"
        const val VIDEO_FILE_SCRIPT = "typeof videoFile !== 'undefined' ? videoFile : ''"
    }
}
