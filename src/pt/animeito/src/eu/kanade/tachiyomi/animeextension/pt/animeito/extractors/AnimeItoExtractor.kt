package eu.kanade.tachiyomi.animeextension.pt.animeito.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class AnimeItoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val sourceUserAgent by lazy { headers["User-Agent"] ?: DEFAULT_USER_AGENT }
    private val videoHeaders by lazy {
        Headers.Builder()
            .set("User-Agent", sourceUserAgent)
            .set("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            .set("Accept-Encoding", "identity")
            .set("Sec-Fetch-Dest", "video")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "cross-site")
            .set("Priority", "u=4")
            .set("Pragma", "no-cache")
            .set("Cache-Control", "no-cache")
            .build()
    }

    suspend fun videosFromUrl(url: String): List<Video> {
        val playerDoc = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
        val script = playerDoc.selectFirst("script:containsData(atob):containsData(charCodeAt)")
            ?.data()
            ?.let(::extractXorObfuscatedPayload)
            ?: return emptyList()
        val (parentOrigin, token) = extractPlayerDataToken(script)
            ?: return emptyList()
        val sources = fetchFreshSources(url, parentOrigin, token)

        return sources.toVideos(url)
    }

    private suspend fun List<Source>.toVideos(referer: String): List<Video> = flatMap { source ->
        if (source.isHls()) {
            playlistUtils.extractFromHls(
                source.file,
                referer = referer,
                videoNameGen = { "Animei.to - $it" },
            )
        } else {
            listOf(
                Video(
                    url = source.file,
                    quality = "Animei.to - ${source.label}",
                    videoUrl = source.file,
                    headers = videoHeaders,
                ),
            )
        }
    }
        .distinctBy { it.videoUrl }
        .sortedByDescending { it.qualityValue() }

    private fun extractXorObfuscatedPayload(script: String): String? {
        val stringsArrayStart = XOR_FUNC_CALL_REGEX.find(script)?.range?.last
            ?: return null
        val stringsArray = script.extractBalanced(stringsArrayStart, '[', ']')
            ?: return null
        val indicesArrayStart = script.indexOf('[', stringsArrayStart + stringsArray.length)
            .takeIf { it != -1 }
            ?: return null
        val indicesArray = script.extractBalanced(indicesArrayStart, '[', ']')
            ?: return null
        val xorKeyBase64 = XOR_KEY_REGEX.find(script, indicesArrayStart + indicesArray.length)
            ?.groupValues
            ?.get(1)
            ?: return null

        val strings = STRING_LITERAL_REGEX.findAll(stringsArray).map { it.groupValues[1] }.toList()
        val indices = INTEGER_REGEX.findAll(indicesArray).map { it.value.toInt() }.toList()
        val joinedBase64 = indices.joinToString("") { strings[it] }
        val decodedPayload = Base64.decode(joinedBase64, Base64.DEFAULT)
        val xorKey = Base64.decode(xorKeyBase64, Base64.DEFAULT)

        val decrypted = ByteArray(decodedPayload.size) { index ->
            (decodedPayload[index].toInt() xor xorKey[index % xorKey.size].toInt()).toByte()
        }

        return String(decrypted, Charsets.UTF_8)
    }

    private suspend fun fetchFreshSources(
        playerUrl: String,
        parentOrigin: String,
        token: String,
    ): List<Source> {
        val freshUrl = playerUrl.toHttpUrl().newBuilder()
            .setQueryParameter("player_data", "1")
            .setQueryParameter("parent_origin", parentOrigin)
            .setQueryParameter("player_data_token", token)
            .setQueryParameter("nocache", "1")
            .setQueryParameter("_stream_refresh", System.currentTimeMillis().toString())
            .build()
            .toString()
        val dataHeaders = headers.newBuilder()
            .set("User-Agent", sourceUserAgent)
            .set("Referer", playerUrl)
            .set("Accept", "application/json, text/plain, */*")
            .set("Cache-Control", "no-cache")
            .build()
        val response = client.newCall(GET(freshUrl, dataHeaders)).awaitSuccess()
        val data = JSONObject(response.body.string())

        return if (data.optBoolean("ok")) {
            data.optJSONArray("sources")?.toSources().orEmpty()
        } else {
            emptyList()
        }
    }

    private fun extractPlayerDataToken(script: String): Pair<String, String>? {
        val objectStart = script.indexOf("playerDataTokens")
            .takeIf { it != -1 }
            ?.let { script.indexOf('{', it) }
            ?.takeIf { it != -1 }
            ?: return null
        val tokens = JSONObject(script.extractBalanced(objectStart, '{', '}') ?: return null)
        val refererOrigin = headers["Referer"]?.toHttpUrlOrNull()
            ?.let { "${it.scheme}://${it.host}" }
        val origins = listOfNotNull(refererOrigin, DEFAULT_PARENT_ORIGIN) + tokens.keys().asSequence().toList()

        return origins.distinct().firstNotNullOfOrNull { origin ->
            tokens.optString(origin).takeIf(String::isNotBlank)?.let { origin to it }
        }
    }

    private fun JSONArray.toSources(): List<Source> {
        return List(length()) { index -> getJSONObject(index) }
            .mapNotNull { item ->
                val file = item.optString("file").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                Source(
                    file = file,
                    label = item.optString("label").takeIf(String::isNotBlank) ?: qualityFromUrl(file),
                    type = item.optString("type"),
                )
            }
    }

    private fun String.extractBalanced(openIndex: Int, openChar: Char, closeChar: Char): String? {
        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (index in openIndex until length) {
            val char = this[index]

            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }

            when (char) {
                '"', '\'', '`' -> quote = char
                openChar -> depth += 1
                closeChar -> {
                    depth -= 1
                    if (depth == 0) return substring(openIndex, index + 1)
                }
            }
        }

        return null
    }

    private fun Source.isHls(): Boolean = file.endsWith(".m3u8", ignoreCase = true) || type.contains("mpegurl", ignoreCase = true)

    private fun qualityFromUrl(url: String): String {
        val itag = ITAG_REGEX.find(url)?.groupValues?.get(1)
        return ITAG_QUALITY_MAP[itag] ?: QUALITY_REGEX.find(url)?.value ?: "Video"
    }

    private fun Video.qualityValue(): Int = REGEX_QUALITY.find(quality)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: 0

    private data class Source(
        val file: String,
        val label: String,
        val type: String,
    )

    companion object {
        private const val DEFAULT_PARENT_ORIGIN = "https://animesonline.io"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

        private val STRING_LITERAL_REGEX = Regex(""""((?:\\.|[^"\\])*)"""")
        private val XOR_FUNC_CALL_REGEX = Regex("""\}\s*_[0-9A-Fa-f]+\s*\(\s*\[""")
        private val XOR_KEY_REGEX = Regex("""[,\s]+"([A-Za-z0-9+/=]+)"""")
        private val INTEGER_REGEX = Regex("""\d+""")
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
        private val REGEX_QUALITY = Regex("""(\d+)p""")
        private val ITAG_REGEX = Regex("""[?&]itag=(\d+)""")
        private val ITAG_QUALITY_MAP = mapOf(
            "18" to "360p",
            "22" to "720p",
            "37" to "1080p",
            "38" to "3072p",
            "59" to "480p",
            "78" to "480p",
        )
    }
}
