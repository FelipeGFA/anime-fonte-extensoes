package eu.kanade.tachiyomi.animeextension.pt.animeq.extractors

import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class UniversalExtractor(private val client: OkHttpClient) {
    private val tag by lazy { javaClass.simpleName }

    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?): List<Video> {
        Log.d(tag, "Fetching videos from: $origRequestUrl")

        val host = origRequestUrl
            .substringAfter("://")
            .removePrefix("www.")
            .substringBefore(".")
            .proper()
        val prefix = name ?: host

        // Fetch the page HTML
        val request = Request.Builder()
            .url(origRequestUrl)
            .headers(origRequestHeader)
            .build()

        val html = runCatching {
            client.newCall(request).execute().use { it.body.string() }
        }.getOrElse {
            Log.e(tag, "Failed to fetch page: ${it.message}")
            return emptyList()
        }

        // Extract the video URL from: var jw = {"file":"<URL>"
        val videoUrl = JW_FILE_REGEX.find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")

        if (videoUrl.isNullOrBlank()) {
            Log.e(tag, "Could not find video URL in page")
            return emptyList()
        }

        Log.d(tag, "Found video URL: $videoUrl")

        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }

        return when {
            "m3u8" in videoUrl -> {
                Log.d(tag, "m3u8 URL: $videoUrl")
                playlistUtils.extractFromHls(videoUrl, origRequestUrl, videoNameGen = { "$prefix: $it" })
            }
            "mpd" in videoUrl -> {
                Log.d(tag, "mpd URL: $videoUrl")
                playlistUtils.extractFromDash(videoUrl, { "$prefix: $it" }, referer = origRequestUrl)
            }
            "mp4" in videoUrl -> {
                Log.d(tag, "mp4 URL: $videoUrl")
                Video(videoUrl, "$prefix: MP4", videoUrl, Headers.headersOf("referer", origRequestUrl)).let(::listOf)
            }
            else -> {
                Log.d(tag, "Unknown format, trying as direct URL: $videoUrl")
                Video(videoUrl, "$prefix: Video", videoUrl, Headers.headersOf("referer", origRequestUrl)).let(::listOf)
            }
        }
    }

    private fun String.proper(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    companion object {
        private val JW_FILE_REGEX = Regex("""var\s+jw\s*=\s*\{["\s]*file["\s]*:\s*"([^"]+)"""")
    }
}
