package eu.kanade.tachiyomi.animeextension.pt.redecanais.detailsproxy

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist.VideoListBootstrapInterceptor
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale

internal fun Request.shouldProxyDetails(baseHost: String): Boolean {
    if (method != "GET" || url.host != baseHost) return false
    if (url.encodedPath == "/__rc_thumb") return false
    if (header(VideoListBootstrapInterceptor.HEADER) != null) return false
    if (header("Accept")?.contains("image/", ignoreCase = true) == true) return false

    val path = url.encodedPath.lowercase(Locale.ROOT)
    if (path in IGNORED_DETAILS_PATHS) return false
    if (IGNORED_DETAILS_PREFIXES.any(path::startsWith)) return false

    return path.substringAfterLast('/').endsWith(".html")
}

internal fun WebResourceRequest.blockedDetailsResponse(baseHost: String): WebResourceResponse? {
    val host = url.host.orEmpty()
    val path = url.encodedPath.orEmpty().lowercase(Locale.ROOT)
    val mimeType = when {
        host == baseHost && path == "/favicon.ico" -> "image/x-icon"
        host == baseHost && path == "/player3/bundle.js" -> "application/javascript"
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

private val IGNORED_DETAILS_PATHS = setOf(
    "/",
    "/topvideos.html",
    "/newvideos.html",
    "/search.php",
)

private val IGNORED_DETAILS_PREFIXES = listOf(
    "/embed.php",
    "/player3/",
    "/videos.php",
)
