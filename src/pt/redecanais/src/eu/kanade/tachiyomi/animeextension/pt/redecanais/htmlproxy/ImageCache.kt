package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ImageCache {

    private val cache = object : LinkedHashMap<String, CachedImage>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedImage>?): Boolean = size > MAX_ENTRIES
    }
    private val pending = HashMap<String, CountDownLatch>()

    @Synchronized
    fun expect(url: String) {
        if (url.isEmpty() || cache.containsKey(url)) return
        pending.getOrPut(url) { CountDownLatch(1) }
    }

    @Synchronized
    fun put(url: String, contentType: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            pending.remove(url)?.countDown()
            return
        }
        cache[url] = CachedImage(
            contentType = contentType.takeIf { it.startsWith("image/") } ?: "image/jpeg",
            bytes = bytes,
        )
        pending.remove(url)?.countDown()
    }

    @Synchronized
    fun get(url: String): CachedImage? = cache[url]

    fun getOrWait(url: String, timeoutMillis: Long): CachedImage? {
        get(url)?.let { return it }
        val latch = synchronized(this) { pending[url] } ?: return null
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return get(url)
    }

    @Synchronized
    fun finish(url: String) {
        pending.remove(url)?.countDown()
    }

    class CachedImage(
        private val contentType: String,
        private val bytes: ByteArray,
    ) {
        fun toResponse(request: Request, protocol: Protocol): Response = Response.Builder()
            .request(request)
            .protocol(protocol)
            .code(200)
            .message("OK")
            .header("Content-Type", contentType)
            .header("Content-Length", bytes.size.toString())
            .body(bytes.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    private const val MAX_ENTRIES = 160
}
