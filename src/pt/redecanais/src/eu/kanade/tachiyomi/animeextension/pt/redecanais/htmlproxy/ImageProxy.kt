package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Locale

class ImageProxy(
    baseUrl: String,
) : Interceptor {

    private val baseHost = baseUrl.toHttpUrl().host

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.shouldProxyImage()) return chain.proceed(request)

        val protocol = chain.connection()?.protocol() ?: Protocol.HTTP_1_1
        val url = request.url.toString()
        val normalizedUrl = url.toRedeCanaisHost(baseHost)
        return ImageCache.getOrWait(url, IMAGE_WAIT_TIMEOUT_MS)?.toResponse(request, protocol)
            ?: ImageCache.getOrWait(normalizedUrl, IMAGE_WAIT_TIMEOUT_MS)?.toResponse(request, protocol)
            ?: imageFailureResponse(request, protocol)
    }

    private fun Request.shouldProxyImage(): Boolean {
        if (method != "GET" || (url.host != baseHost && !url.host.startsWith("redecanais."))) return false

        val path = url.encodedPath.lowercase(Locale.ROOT)
        if (path == "/__rc_thumb") return false

        return header("Accept")?.contains("image/", ignoreCase = true) == true ||
            IMAGE_EXTENSIONS.any(path::endsWith)
    }

    private fun imageFailureResponse(request: Request, protocol: Protocol): Response = Response.Builder()
        .request(request)
        .protocol(protocol)
        .code(504)
        .message("Image Not Cached")
        .headers(
            Headers.headersOf(
                "Content-Type",
                "text/plain",
                "X-RedeCanais-Image-Proxy-Error",
                "true",
            ),
        )
        .body("RedeCanais image not cached: ${request.url}".toResponseBody("text/plain".toMediaTypeOrNull()))
        .build()

    private companion object {
        const val IMAGE_WAIT_TIMEOUT_MS = 15_000L
        val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
    }
}
