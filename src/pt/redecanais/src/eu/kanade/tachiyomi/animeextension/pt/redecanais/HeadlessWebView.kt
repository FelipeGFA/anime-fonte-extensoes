package eu.kanade.tachiyomi.animeextension.pt.redecanais

import android.webkit.WebView

internal fun WebView.destroyHeadless(vararg javascriptInterfaces: String) {
    stopLoading()
    javascriptInterfaces.forEach(::removeJavascriptInterface)
    webChromeClient = null
    removeAllViews()
    destroy()
}
