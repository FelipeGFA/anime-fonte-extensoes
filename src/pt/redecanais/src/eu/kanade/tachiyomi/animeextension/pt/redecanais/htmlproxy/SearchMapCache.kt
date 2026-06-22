package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object SearchMapCache {
    val files = listOf("final_mapa.txt", "final_mapafilmes.txt")

    private val values = ConcurrentHashMap<String, String>()

    fun put(file: String, text: String) {
        val key = file.substringAfterLast('/').lowercase(Locale.ROOT)
        if (key in files && text.isNotBlank()) values[key] = text
    }

    fun isReady(): Boolean = files.all(values::containsKey)

    fun snapshot(): List<Pair<String, String>> = files.mapNotNull { file ->
        values[file]?.let { file to it }
    }
}
