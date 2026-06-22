package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class P2PExtractor(
    client: OkHttpClient,
    headers: Headers,
) {
    private val extractor = AniKyuEncryptedExtractor(client, headers)

    suspend fun videosFromUrl(url: String, name: String = "P2P"): List<Video> = extractor.videosFromUrl(url, name)
}

class FourMeExtractor(
    client: OkHttpClient,
    headers: Headers,
) {
    private val extractor = AniKyuEncryptedExtractor(client, headers)

    suspend fun videosFromUrl(url: String, name: String = "4me"): List<Video> = extractor.videosFromUrl(url, name)
}

class EzplayerExtractor(
    client: OkHttpClient,
    headers: Headers,
) {
    private val extractor = AniKyuEncryptedExtractor(client, headers)

    suspend fun videosFromUrl(url: String, name: String = "Ezplayer"): List<Video> = extractor.videosFromUrl(url, name)
}

private class AniKyuEncryptedExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    suspend fun videosFromUrl(url: String, name: String): List<Video> {
        val playerUrl = url.toHttpUrl()
        val videoId = playerUrl.fragment?.substringBefore("&")?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val origin = playerUrl.origin()
        val videoHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", origin)
            .set("Referer", "$origin/")
            .build()

        val apiUrl = playerUrl.newBuilder()
            .encodedPath("/api/v1/video")
            .query(null)
            .fragment(null)
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", DEFAULT_WIDTH)
            .addQueryParameter("h", DEFAULT_HEIGHT)
            .addQueryParameter("r", REFERER_HOST)
            .build()

        val payload = client.newCall(GET(apiUrl.toString(), videoHeaders))
            .awaitSuccess()
            .bodyString()
            .decryptPayload()
            .replace("\\#", "#")

        val data = JSONObject(payload)
        val sources = data.extractSources(playerUrl)

        return sources.flatMap { source ->
            when {
                source.url.endsWith(".mp4", ignoreCase = true) -> listOf(
                    Video(
                        url = source.url,
                        quality = "$name - ${source.name}",
                        videoUrl = source.url,
                        headers = videoHeaders,
                    ),
                )
                else -> PlaylistUtils(client, videoHeaders).extractFromHls(
                    playlistUrl = source.url,
                    referer = "$origin/",
                    masterHeadersGen = { _, _ -> videoHeaders },
                    videoHeadersGen = { _, _, _ -> videoHeaders },
                    videoNameGen = { "$name - ${source.name} - $it" },
                )
            }
        }.distinctBy { it.videoUrl }
    }

    private fun JSONObject.extractSources(playerUrl: HttpUrl): List<Source> {
        val streamingConfig = optString("streamingConfig")
            .takeIf(String::isNotBlank)
            ?.let(::JSONObject)

        val order = streamingConfig?.optJSONArray("order")?.toStringList()
            ?: DEFAULT_SOURCE_ORDER
        val adjust = streamingConfig?.optJSONObject("adjust")

        return order.mapNotNull { sourceName ->
            val url = optString(SOURCE_FIELDS[sourceName] ?: return@mapNotNull null)
                .takeIf(String::isNotBlank)
                ?.toSourceUrl(playerUrl)
                ?: return@mapNotNull null

            val sourceConfig = adjust?.optJSONObject(sourceName)
            if (sourceConfig?.optBoolean("disabled") == true) {
                return@mapNotNull null
            }

            Source(
                name = sourceName,
                url = url.withParams(sourceConfig?.optJSONObject("params")),
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> = List(length()) { index -> optString(index) }
        .filter(String::isNotBlank)

    private fun String.toSourceUrl(playerUrl: HttpUrl): String {
        val fixedUrl = when {
            startsWith("//") -> "https:$this"
            else -> this
        }

        return fixedUrl.toHttpUrlOrNull()?.toString()
            ?: playerUrl.resolve(fixedUrl)?.toString()
            ?: fixedUrl
    }

    private fun String.withParams(params: JSONObject?): String {
        if (params == null || params.length() == 0) return this

        val httpUrl = toHttpUrlOrNull() ?: return this
        return httpUrl.newBuilder().apply {
            for (key in params.keys()) {
                val value = params.optString(key)
                if (value.isNotBlank()) {
                    setQueryParameter(key, value)
                }
            }
        }.build().toString()
    }

    private fun String.decryptPayload(): String {
        val hex = trim().let { body ->
            if (body.matches(HEX_REGEX)) {
                body
            } else {
                String(Base64.decode(body, Base64.DEFAULT)).trim()
            }
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY.toByteArray(), "AES"),
            IvParameterSpec(IV.toByteArray()),
        )

        return String(cipher.doFinal(hex.hexToBytes()), Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        val cleanHex = trim()
        return ByteArray(cleanHex.length / 2) { index ->
            cleanHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun HttpUrl.origin(): String = "$scheme://$host"

    private data class Source(
        val name: String,
        val url: String,
    )

    companion object {
        private const val KEY = "kiemtienmua911ca"
        private const val IV = "1234567890oiyutr"
        private const val DEFAULT_WIDTH = "1920"
        private const val DEFAULT_HEIGHT = "1080"
        private const val REFERER_HOST = "anikyuu.to"

        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
        private val DEFAULT_SOURCE_ORDER = listOf("Cloudflare", "In-House")
        private val SOURCE_FIELDS = mapOf(
            "Cloudflare" to "cf",
            "Tiktok" to "hlsVideoTiktok",
            "Google" to "hlsVideoGoogle",
            "In-House" to "source",
        )
    }
}
