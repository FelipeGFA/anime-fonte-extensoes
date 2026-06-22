package eu.kanade.tachiyomi.animeextension.pt.animeito

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.pt.animeito.extractors.AnimeItoExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeIto :
    AnimeStream(
        "pt-BR",
        "Animeito",
        "https://animesonline.io",
    ) {
    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    // ============================ Video Links =============================
    override val prefQualityDefault = "1080p"
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    override fun videoListSelector() = "ul.tabs_videos li"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val directTokenUrls = document.select("#embed_holder iframe[data-src]").mapNotNull {
            it.attr("abs:data-src").ifBlank { it.attr("data-src") }
                .takeIf { url -> url.startsWith("http") }
        }

        if (directTokenUrls.isNotEmpty()) {
            return directTokenUrls.distinct().parallelCatchingFlatMapBlocking { url ->
                getVideoList(url, "AnimeIto")
            }
        }

        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            getVideoList(url, name)
        }
    }

    override suspend fun getHosterUrl(element: Element): String {
        val encodedData = element.attr("value")

        return getHosterUrl(encodedData)
    }

    override suspend fun getHosterUrl(encodedData: String): String {
        val decodedDocument = runCatching {
            Jsoup.parse(String(Base64.decode(encodedData, Base64.DEFAULT)))
        }.getOrNull()

        return decodedDocument
            ?.selectFirst("#embed_holder iframe[data-src]")
            ?.safePlayerUrl("data-src")
            ?: decodedDocument
                ?.selectFirst("#embed_holder iframe[src]")
                ?.safePlayerUrl("src")
            ?: super.getHosterUrl(encodedData)
    }

    private val animeitoExtractor by lazy { AnimeItoExtractor(client, headers) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when {
        "anidrive.click" in url -> animeitoExtractor.videosFromUrl(url)
        else -> emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality, true) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
    }

    private fun Element.safePlayerUrl(attribute: String): String? = attr("abs:$attribute").ifBlank { attr(attribute) }
        .takeIf { it.startsWith("http") }
}
