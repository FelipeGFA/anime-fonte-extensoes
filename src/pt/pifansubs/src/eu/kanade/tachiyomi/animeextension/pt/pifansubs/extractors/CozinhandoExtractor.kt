package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class CozinhandoExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(url: String): List<Video> {
        val embedUrl = resolveEmbedUrl(url) ?: return emptyList()

        return when {
            "fsst.online" in embedUrl || "incvideo" in embedUrl -> videosFromIncVideo(embedUrl)
            else -> emptyList()
        }
    }

    private fun resolveEmbedUrl(url: String): String? {
        val parsedUrl = url.toHttpUrlOrNull() ?: return null

        return when {
            parsedUrl.host.endsWith("cozinhando.click") -> {
                parsedUrl.queryParameter("t")
                    ?.let(::decodePayloadUrl)
                    ?: decodeProxyRedirect(url)
            }

            parsedUrl.host.endsWith("holuagency.top") -> {
                parsedUrl.queryParameter("auth")?.let(::decodePayloadUrl)
            }

            else -> url
        }
    }

    private fun decodePayloadUrl(encodedPayload: String): String? {
        val decoded = runCatching { decodeBase64(encodedPayload) }.getOrNull() ?: return null
        return runCatching { decoded.parseAs<CozinhandoPayload>().url }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun decodeProxyRedirect(url: String): String? {
        val page = client.newCall(GET(url, headers)).execute().use { it.body.string() }
        val script = proxyScriptRegex.find(page)?.groupValues?.get(1) ?: return null
        val decodedScript = runCatching { decodeBase64(script) }.getOrNull() ?: return null
        val redirect = redirectRegex.find(decodedScript)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
            ?.let { runCatching { decodeBase64(it) }.getOrNull() }
            ?: return null
        val token = redirect.toHttpUrlOrNull()?.queryParameter("tk") ?: return null
        val payload = decodeJwtPayload(token) ?: return null
        val finalUrl = runCatching { payload.parseAs<RedirectPayload>().finalUrl }.getOrNull()
            ?: return null

        return finalUrl.toHttpUrlOrNull()
            ?.queryParameter("auth")
            ?.let(::decodePayloadUrl)
    }

    private fun videosFromIncVideo(url: String): List<Video> {
        return client.newCall(GET(url, headers)).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val doc = response.asJsoup()
            val script = doc.select("script:containsData(file)")
                .joinToString("\n") { it.data() }
            val fileList = fileRegex.find(script)?.groupValues?.get(1) ?: return emptyList()
            val videoHeaders = headers.newBuilder()
                .set("Referer", finalUrl)
                .build()

            fileEntryRegex.findAll(fileList)
                .map {
                    val label = it.groupValues[1]
                    val file = it.groupValues[2]
                    Video(file, "Cozinhando - $label", file, videoHeaders)
                }
                .toList()
        }
    }

    private fun decodeJwtPayload(token: String): String? {
        val encodedPayload = token.split('.').getOrNull(1) ?: return null
        val normalized = encodedPayload
            .replace('-', '+')
            .replace('_', '/')
        val padded = normalized + when (normalized.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }

        return runCatching { decodeBase64(padded) }.getOrNull()
    }

    private fun decodeBase64(value: String): String = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)

    private companion object {
        val proxyScriptRegex = Regex("""new Function\(atob\("([^"]+)"""")
        val redirectRegex = Regex("""D=atob\("([^"]+)"""")
        val fileRegex = Regex("""file\s*:\s*"([^"]+)"""")
        val fileEntryRegex = Regex("""\[(.+?)](https?://[^,]+)""")
    }
}

@Serializable
private class CozinhandoPayload(val url: String)

@Serializable
private class RedirectPayload(@SerialName("final") val finalUrl: String)
