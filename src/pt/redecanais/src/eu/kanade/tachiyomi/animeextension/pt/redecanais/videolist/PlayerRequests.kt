package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

internal fun WebResourceRequest.blockedPlayerResponse(baseHost: String): WebResourceResponse? {
    val host = url.host.orEmpty()
    val path = url.encodedPath.orEmpty().lowercase(Locale.ROOT)
    val mimeType = when {
        host == baseHost && path == "/favicon.ico" -> "image/x-icon"
        host == "acscdn.com" || host == "static.cloudflareinsights.com" -> "application/javascript"
        else -> return null
    }

    return WebResourceResponse(
        mimeType,
        "utf-8",
        200,
        "OK",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}
