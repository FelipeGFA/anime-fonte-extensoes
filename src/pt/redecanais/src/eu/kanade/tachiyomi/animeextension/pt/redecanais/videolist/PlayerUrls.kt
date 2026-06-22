package eu.kanade.tachiyomi.animeextension.pt.redecanais.videolist

import eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy.toRedeCanaisHost

internal fun String.toAbsoluteRedeCanaisUrl(baseUrl: String, baseHost: String): String = when {
    startsWith("//") -> "https:$this"
    startsWith("/") -> "$baseUrl$this"
    startsWith("http", ignoreCase = true) -> toRedeCanaisHost(baseHost)
    else -> "$baseUrl/${trimStart('/')}"
}

internal fun String.toAbsoluteVideoUrl(baseUrl: String): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http", ignoreCase = true) -> this
    startsWith("/") -> "$baseUrl$this"
    else -> "$baseUrl/${trimStart('/')}"
}
