package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import android.util.Log
import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class PlayerBridge(
    private val baseUrl: String,
    private val baseHost: String,
    private val onResult: (PlayerApiSniffer.Result?) -> Unit,
) {
    private val tag by lazy { javaClass.simpleName }
    private val finished = AtomicBoolean(false)
    private val token = AtomicInteger(0)

    @JavascriptInterface
    @Suppress("UNUSED")
    fun passResult(token: Int, iframeUrl: String, videoUrl: String) {
        val currentToken = this.token.get()
        if (token != currentToken) {
            Log.d(tag, "passResult ignored stale token=$token current=$currentToken iframe=${iframeUrl.takeLast(80)} video=${videoUrl.takeLast(80)}")
            return
        }
        if (!finished.compareAndSet(false, true)) {
            Log.d(tag, "passResult ignored duplicate token=$token iframe=${iframeUrl.takeLast(80)} video=${videoUrl.takeLast(80)}")
            return
        }

        val referer = iframeUrl.toAbsoluteRedeCanaisUrl(baseUrl, baseHost)
        val result = videoUrl.takeIf { it.isNotBlank() }
            ?.let { PlayerApiSniffer.Result(it.toAbsoluteVideoUrl(baseUrl), referer) }
        Log.d(tag, "passResult accepted token=$token referer=${referer.takeLast(80)} video=${result?.url?.takeLast(80).orEmpty()}")
        onResult(result)
    }

    fun hasFinished(): Boolean = finished.get()

    fun reset(token: Int) {
        this.token.set(token)
        finished.set(false)
        Log.d(tag, "reset token=$token")
    }
}
