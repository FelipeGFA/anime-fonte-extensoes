package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.Locale

internal fun Request.shouldProxyPageHtml(baseHost: String): Boolean {
    if (method != "GET") return false
    if (url.host != baseHost) return false
    if (url.encodedPath == "/__rc_thumb") return false
    if (url.pathSegments.firstOrNull() == "cdn-cgi") return false
    if (isStaticRequest()) return false
    if (header("Accept")?.contains("image/", ignoreCase = true) == true) return false

    return header("Accept")?.contains("text/html", ignoreCase = true) == true ||
        url.encodedPath == "/" ||
        url.encodedPath.endsWith(".html", ignoreCase = true) ||
        url.encodedPath.substringAfterLast('/').substringAfterLast('.').isEmpty()
}

internal fun WebResourceRequest.blockedResponse(
    baseHost: String,
    pageUrl: String?,
): WebResourceResponse? {
    val host = url.host.orEmpty()
    val path = url.encodedPath.orEmpty().lowercase(Locale.ROOT)
    val listingPage = pageUrl.isListingPageUrl()
    val mimeType = when {
        host == baseHost && path == "/favicon.ico" -> "image/x-icon"
        listingPage && host == baseHost && path == "/player3/bundle.js" -> "application/javascript"
        listingPage && host in LISTING_BLOCKED_SCRIPT_HOSTS -> "application/javascript"
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

internal fun String?.isListingPageUrl(): Boolean {
    val path = this?.toHttpUrlOrNull()?.encodedPath?.lowercase(Locale.ROOT) ?: return false
    return path == "/" ||
        path.endsWith("/topvideos.html") ||
        path.endsWith("/newvideos.html") ||
        path.endsWith("/search.php")
}

internal fun String?.isFastListingPageUrl(): Boolean {
    val path = this?.toHttpUrlOrNull()?.encodedPath?.lowercase(Locale.ROOT) ?: return false
    return path == "/" ||
        path.endsWith("/topvideos.html") ||
        path.endsWith("/newvideos.html")
}

private fun Request.isStaticRequest(): Boolean {
    val path = url.encodedPath.lowercase(Locale.ROOT)
    return STATIC_EXTENSIONS.any(path::endsWith)
}
