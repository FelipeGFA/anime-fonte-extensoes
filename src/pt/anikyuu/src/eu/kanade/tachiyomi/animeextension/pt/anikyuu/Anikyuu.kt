package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import android.util.Base64
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.ByseExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.EzplayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.FourMeExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.P2PExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.StrmupExtractor
import eu.kanade.tachiyomi.animeextension.pt.anikyuu.extractors.TurbovidExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anikyuu :
    AnimeStream(
        "pt-BR",
        "Anikyuu",
        "https://anikyuu.to",
    ) {
    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================ Video Links =============================
    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================

    private val byseExtractor by lazy { ByseExtractor(client, headers, baseUrl) }
    private val ezplayerExtractor by lazy { EzplayerExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val fourMeExtractor by lazy { FourMeExtractor(client, headers) }
    private val p2pExtractor by lazy { P2PExtractor(client, headers) }
    private val strmupExtractor by lazy { StrmupExtractor(client, headers) }
    private val turbovidExtractor by lazy { TurbovidExtractor(client, headers) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when {
        "filemoon" in url -> filemoonExtractor.videosFromUrl(url)
        "strmup.to" in url -> strmupExtractor.videosFromUrl(url)
        "byselapuix.com" in url -> byseExtractor.videosFromUrl(url)
        "emturbovid.com" in url -> turbovidExtractor.videosFromUrl(url, name)
        "anikyuup2p.site" in url -> p2pExtractor.videosFromUrl(url, name)
        "4meplayer" in url -> fourMeExtractor.videosFromUrl(url, name)
        "ezplayer" in url -> ezplayerExtractor.videosFromUrl(url, name)

        else -> emptyList()
    }

    override suspend fun getHosterUrl(encodedData: String): String {
        if (encodedData.startsWith("http", ignoreCase = true)) {
            return super.getHosterUrl(encodedData)
        }

        val decoded = String(Base64.decode(encodedData, Base64.DEFAULT))
        return Jsoup.parse(decoded)
            .selectFirst("iframe[src], iframe[data-src]")
            ?.safeIframeUrl()
            ?: super.getHosterUrl(encodedData)
    }

    private fun Element.safeIframeUrl(): String? {
        val value = when {
            hasAttr("src") -> attr("src")
            hasAttr("data-src") -> attr("data-src")
            else -> return null
        }.trim()

        return when {
            value.startsWith("data:", ignoreCase = true) -> null
            value.startsWith("http", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            else -> absUrl(if (hasAttr("src")) "src" else "data-src").ifBlank { value }
        }
    }
}
