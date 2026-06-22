package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ByseExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    suspend fun videosFromUrl(url: String): List<Video> {
        val id = url.toHttpUrl().pathSegments.getOrNull(1) ?: return emptyList()
        val embedUrl = getEmbedUrl(url, id) ?: return emptyList()
        val playlist = resolveMasterPlaylist(url, embedUrl, id) ?: return emptyList()
        val videoHeaders = buildVideoHeaders(playlist.frameUrl ?: embedUrl)

        return PlaylistUtils(client, videoHeaders).extractFromHls(
            playlistUrl = playlist.url,
            referer = videoHeaders["Referer"] ?: (playlist.frameUrl ?: embedUrl),
            masterHeadersGen = { _, _ -> videoHeaders },
            videoHeadersGen = { _, _, _ -> videoHeaders },
            videoNameGen = { "Byse - $it" },
        )
    }

    private suspend fun getEmbedUrl(url: String, id: String): String? {
        val detailsHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("X-Embed-Parent", url)
            .set("X-Embed-Referer", baseUrl)
            .set("X-Embed-Origin", baseUrl.toHttpUrl().host)
            .build()

        return client.newCall(GET("https://${url.toHttpUrl().host}/api/videos/$id/embed/details", detailsHeaders))
            .awaitSuccess()
            .bodyString()
            .substringAfter("\"embed_frame_url\"", "")
            .substringAfter(":")
            .substringAfter('"')
            .substringBefore('"')
            .takeIf(String::isNotBlank)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveMasterPlaylist(pageUrl: String, embedUrl: String, id: String): ResolvedPlaylist? {
        val latch = CountDownLatch(1)
        val embedHost = embedUrl.toHttpUrl().host
        val loadHeaders = headers.toWebViewHeaders().apply {
            put("Referer", baseUrl)
            put("X-Embed-Origin", baseUrl.toHttpUrl().host)
            put("X-Embed-Parent", pageUrl)
            put("X-Embed-Referer", baseUrl)
        }
        var webView: WebView? = null
        var masterPlaylist: ResolvedPlaylist? = null
        var frameUrl: String? = null

        handler.post {
            val currentWebView = WebView(context)
            webView = currentWebView

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(currentWebView, true)
            }

            with(currentWebView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = false
                loadWithOverviewMode = false
                headers["User-Agent"]?.let { userAgentString = it }
            }

            currentWebView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val requestUrl = request.url.toString()
                    return when {
                        requestUrl.isByseMasterPlaylist() && masterPlaylist == null -> {
                            masterPlaylist = ResolvedPlaylist(requestUrl, frameUrl)
                            latch.countDown()
                            super.shouldInterceptRequest(view, request)
                        }
                        requestUrl.sameUrlIgnoringQuery(pageUrl) -> patchHtmlDocument(request)
                            ?: super.shouldInterceptRequest(view, request)
                        requestUrl.isByseEmbedDocument(embedHost, id) -> {
                            frameUrl = requestUrl
                            patchHtmlDocument(request) ?: super.shouldInterceptRequest(view, request)
                        }
                        else -> super.shouldInterceptRequest(view, request)
                    }
                }
            }

            currentWebView.loadUrl(pageUrl, loadHeaders)
        }

        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return masterPlaylist
    }

    private fun patchHtmlDocument(request: WebResourceRequest): WebResourceResponse? {
        val response = client.newCall(GET(request.url.toString(), request.requestHeaders.toOkHttpHeaders())).execute()
        response.use {
            if (!it.isSuccessful) {
                return null
            }

            val patchedBody = it.bodyString().injectAutoClickScript()
            return WebResourceResponse(
                "text/html",
                Charsets.UTF_8.name(),
                it.code,
                it.message.ifBlank { "OK" },
                it.headers.toWebResourceHeaders(),
                ByteArrayInputStream(patchedBody.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    private fun buildVideoHeaders(frameUrl: String): Headers = headers.newBuilder().apply {
        set("Accept", "*/*")
        headers["User-Agent"]?.let { set("User-Agent", it) }
        headers["Accept-Language"]?.let { set("Accept-Language", it) }
        set("Origin", frameUrl.origin())
        set("Referer", frameUrl)
    }.build()

    private data class ResolvedPlaylist(
        val url: String,
        val frameUrl: String?,
    )

    private fun Headers.toWebViewHeaders(): MutableMap<String, String> = names()
        .associateWith { get(it).orEmpty() }
        .filterKeys { it.isSafeHeaderName() }
        .filterValues { it.isNotBlank() }
        .toMutableMap()

    private fun Map<String, String>.toOkHttpHeaders(): Headers = headers.newBuilder().apply {
        forEach { (name, value) ->
            if (name.isSafeHeaderName() && value.isNotBlank()) {
                set(name, value)
            }
        }
    }.build()

    private fun Headers.toWebResourceHeaders(): Map<String, String> = names()
        .filter { it.isSafeResponseHeaderName() }
        .associateWith { values(it).joinToString(", ") }

    private fun String.isSafeHeaderName(): Boolean = isNotBlank() && lowercase() !in UNSAFE_REQUEST_HEADERS

    private fun String.isSafeResponseHeaderName(): Boolean = isNotBlank() && lowercase() !in UNSAFE_RESPONSE_HEADERS

    private fun String.origin(): String {
        val httpUrl = toHttpUrl()
        return "${httpUrl.scheme}://${httpUrl.host}"
    }

    private fun String.sameUrlIgnoringQuery(other: String): Boolean = substringBefore("?").trimEnd('/') == other.substringBefore("?").trimEnd('/')

    private fun String.isByseEmbedDocument(embedHost: String, id: String): Boolean {
        val url = toHttpUrlOrNull() ?: return false
        return url.host == embedHost && url.pathSegments.lastOrNull() == id
    }

    private fun String.isByseMasterPlaylist(): Boolean = substringBefore("?").endsWith("/master.m3u8", ignoreCase = true)

    private fun String.injectAutoClickScript(): String {
        val script = "<script>$AUTO_CLICK_SCRIPT</script>"
        return if (contains("</body>", ignoreCase = true)) {
            replace(Regex("</body>", RegexOption.IGNORE_CASE), "$script</body>")
        } else {
            this + script
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 35L

        private val UNSAFE_REQUEST_HEADERS = setOf(
            "accept-encoding",
            "connection",
            "content-length",
            "host",
            "te",
            "transfer-encoding",
        )

        private val UNSAFE_RESPONSE_HEADERS = setOf(
            "content-security-policy",
            "content-security-policy-report-only",
            "content-encoding",
            "content-length",
            "transfer-encoding",
        )

        private val AUTO_CLICK_SCRIPT = """
            (function () {
              if (window.__aniyomiByseAutoClick) return;
              window.__aniyomiByseAutoClick = true;
              var tick = function () {
                var button = document.querySelector('button.captcha-gate__play');
                if (button) button.click();
                try {
                  if (typeof jwplayer === 'function') {
                    var player = jwplayer(0);
                    if (player && typeof player.play === 'function') player.play();
                  }
                } catch (e) {}
                var video = document.querySelector('video');
                if (video && video.paused && typeof video.play === 'function') {
                  video.play().catch(function () {});
                }
              };
              tick();
              setInterval(tick, 500);
            })();
        """.trimIndent()
    }
}
