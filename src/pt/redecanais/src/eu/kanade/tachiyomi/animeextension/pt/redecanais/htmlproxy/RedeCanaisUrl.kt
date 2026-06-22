package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun String.toRedeCanaisHost(baseHost: String): String {
    val url = toHttpUrlOrNull() ?: return this
    if (!url.host.startsWith("redecanais.")) return this
    return url.newBuilder().host(baseHost).build().toString()
}
