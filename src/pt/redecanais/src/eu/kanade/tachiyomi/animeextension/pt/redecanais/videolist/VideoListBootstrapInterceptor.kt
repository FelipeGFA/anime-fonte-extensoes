package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class VideoListBootstrapInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) == null) return chain.proceed(request)

        val cleanRequest = request.newBuilder()
            .removeHeader(HEADER)
            .build()

        return Response.Builder()
            .request(cleanRequest)
            .protocol(chain.connection()?.protocol() ?: Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(Headers.headersOf("Content-Type", "text/html"))
            .body(ByteArray(0).toResponseBody("text/html".toMediaTypeOrNull()))
            .build()
    }

    companion object {
        const val HEADER = "X-RedeCanais-Video-List"
    }
}
