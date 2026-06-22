package eu.kanade.tachiyomi.animeextension.pt.redecanais.detailsproxy

import android.webkit.JavascriptInterface
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch

internal class DetailsBridge(
    private val latch: CountDownLatch,
) {
    @Volatile
    var html: DetailsProxyResult? = null
        private set

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passHtml(contentType: String, body: String) {
        if (html != null) return

        html = DetailsProxyResult(
            contentType = contentType.substringBefore(";").ifEmpty { "text/html" },
            body = body,
        )
        latch.countDown()
    }

    fun hasFinished(): Boolean = html != null
}

internal class DetailsProxyResult(
    private val contentType: String,
    private val body: String,
) {
    fun toResponse(request: Request, protocol: Protocol): Response = Response.Builder()
        .request(request)
        .protocol(protocol)
        .code(200)
        .message("OK")
        .headers(Headers.headersOf("Content-Type", contentType))
        .body(body.toResponseBody(contentType.toMediaTypeOrNull()))
        .build()
}
