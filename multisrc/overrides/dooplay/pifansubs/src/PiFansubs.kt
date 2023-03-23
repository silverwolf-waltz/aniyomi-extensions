package eu.kanade.tachiyomi.animeextension.pt.pifansubs

import android.net.Uri
import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.AdoroDoramasExtractor
import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors.JMVStreamExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PiFansubs : DooPlay(
    "pt-BR",
    "Pi Fansubs",
    "https://pifansubs.org",
) {

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override val PREF_QUALITY_VALUES = arrayOf("360p", "480p", "720p", "1080p")
    override val PREF_QUALITY_ENTRIES = PREF_QUALITY_VALUES

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val players = document.select("div.source-box:not(#source-player-trailer) iframe")
        return players.map(::getPlayerUrl).flatMap(::getPlayerVideos)
    }

    private fun getPlayerUrl(player: Element): String {
        return player.attr("data-src").ifEmpty { player.attr("src") }.let {
            when {
                !it.startsWith("http") -> "https:" + it
                else -> it
            }
        }
    }

    private fun getPlayerVideos(url: String): List<Video> {
        val streamsbDomains = listOf("sbspeed", "sbanh", "streamsb", "sbfull", "sbbrisk")
        return when {
            "player.jmvstream" in url ->
                JMVStreamExtractor(client).videosFromUrl(url)
            "gdriveplayer." in url ->
                GdrivePlayerExtractor(client).videosFromUrl(url)
            streamsbDomains.any { it in url } ->
                StreamSBExtractor(client).videosFromUrl(url, headers)
            "adorodoramas.com" in url ->
                AdoroDoramasExtractor(client).videosFromUrl(url)
            "/jwplayer/?source" in url -> {
                val videoUrl = Uri.parse(url).getQueryParameter("source")!!
                listOf(Video(videoUrl, "JWPlayer", videoUrl, headers))
            }
            else -> emptyList<Video>()
        }
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector p")
            .eachText()
            .joinToString("\n\n") + "\n"
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episodes/page/$page", headers)
}
