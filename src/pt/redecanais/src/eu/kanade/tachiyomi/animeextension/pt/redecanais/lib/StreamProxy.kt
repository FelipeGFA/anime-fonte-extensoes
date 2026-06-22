package eu.kanade.tachiyomi.animeextension.pt.redecanais.lib

import okhttp3.OkHttpClient

internal class StreamProxy(
    private val client: OkHttpClient,
) {
    private var server: StreamServer? = null

    @Synchronized
    fun proxiedUrl(streamUrl: String): String {
        val current = server?.takeIf { it.isRunning() } ?: StreamServer(client).also {
            it.start()
            server = it
        }

        return current.createStreamUrl(streamUrl)
    }
}
