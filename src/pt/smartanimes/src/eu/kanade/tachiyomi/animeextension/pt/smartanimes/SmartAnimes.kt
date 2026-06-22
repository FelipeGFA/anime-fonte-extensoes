package eu.kanade.tachiyomi.animeextension.pt.smartanimes

import eu.kanade.tachiyomi.animeextension.pt.smartanimes.extractors.SmartAnimesExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.Response

class SmartAnimes :
    AnimeStream(
        "pt-BR",
        "SmartAnimes",
        "https://smartanimes.com",
    ) {
    override fun headersBuilder() = super.headersBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        add("Referer", "$baseUrl/")
        add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p")

    // ============================ Video Links =============================
    override fun videoListSelector() = ".dlbox li:not(.head)"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.useAsJsoup().select(videoListSelector())

        return runBlocking {
            items.mapNotNull { element ->
                runCatching {
                    val name = element.selectFirst(".q")!!.text().trim()
                    val quality = element.selectFirst(".w")!!.text().trim()
                    val url = element.selectFirst("a")!!.attr("href")
                    getVideoList(url, "$name - $quality")
                }.getOrNull()
            }.flatten()
        }
    }

    private val smartanimesExtractor by lazy { SmartAnimesExtractor(client, headers) }

    override suspend fun getVideoList(url: String, name: String): List<Video> = when {
        "smartanimes" in url -> smartanimesExtractor.videosFromUrl(url, name)
        else -> emptyList()
    }

    // ============================= Utilities ==============================
    override fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase()) {
        "completo" -> SAnime.COMPLETED
        "em lançamento" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { REGEX_QUALITY.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val REGEX_QUALITY by lazy { Regex("""(\d+)p""") }
    }
}
