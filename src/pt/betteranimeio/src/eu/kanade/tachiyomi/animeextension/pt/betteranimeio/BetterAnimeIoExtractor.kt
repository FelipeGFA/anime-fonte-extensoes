package eu.kanade.tachiyomi.animeextension.pt.betteranimeio

import android.util.Base64
import aniyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

class BetterAnimeIoExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val bloggerExtractor = BloggerExtractor(client)

    /**
     * Decodes the Base64 token, fetches the Blogger video page directly
     * from the user's device using the shared BloggerExtractor library,
     * and extracts the MP4 video URLs.
     */
    suspend fun extractVideosFromBlogger(encodedSource: String): List<Video> {
        // 1. Decode Base64 and reverse the string to get the real Blogger token
        val base64Clean = encodedSource.replace(" ", "+")
        val decoded = String(Base64.decode(base64Clean, Base64.DEFAULT))
        val bloggerToken = decoded.reversed()

        // 2. Fetch the Blogger video page directly from the user's device
        val bloggerUrl = "$BLOGGER_URL$bloggerToken"
        val bloggerHeaders = headers.newBuilder()
            .set("Referer", REFERER)
            .build()

        return bloggerExtractor.videosFromUrl(bloggerUrl, bloggerHeaders)
    }

    companion object {
        private const val BLOGGER_URL = "https://www.blogger.com/video.g?token="
        private const val REFERER = "https://betteranime.io/"
    }
}
