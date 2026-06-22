package eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class TurbovidExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    suspend fun videosFromUrl(url: String, name: String = "Turbovid"): List<Video> {
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", url.toHttpUrl().origin())
            .build()

        val body = client.newCall(GET(url, videoHeaders))
            .awaitSuccess()
            .bodyString()

        val playlistUrl = URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(body)?.value
            ?: return emptyList()

        return PlaylistUtils(client, videoHeaders).extractFromHls(
            playlistUrl = playlistUrl,
            referer = url,
            masterHeadersGen = { _, _ -> videoHeaders },
            videoHeadersGen = { _, _, _ -> videoHeaders },
            videoNameGen = { "$name - $it" },
        )
    }

    private fun okhttp3.HttpUrl.origin(): String = "$scheme://$host"

    companion object {
        private val URL_PLAY_REGEX = Regex("""urlPlay\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
        private val M3U8_REGEX = Regex("""https?://[^'"\s<>]+\.m3u8[^'"\s<>]*""")
    }
}
