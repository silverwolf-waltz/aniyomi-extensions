package eu.kanade.tachiyomi.animeextension.en.kissanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.kissanime.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animeextension.en.kissanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.en.kissanime.extractors.VodstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class KissAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "kissanime.com.ru"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://kissanime.com.ru")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/AnimeListOnline/Trending?page=$page")

    override fun popularAnimeSelector(): String = "div.listing > div.item_movies_in_cat"

    override fun popularAnimeNextPageSelector(): String = "div.pagination > ul > li.current ~ li"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            title = element.selectFirst("div.title_in_cat_container > a")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/AnimeListOnline/LatestUpdate?page=$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = KissAnimeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: KissAnimeFilters.FilterSearchParams): Request {
        return when {
            filters.subpage.isNotBlank() -> GET("$baseUrl/${filters.subpage}/?page=$page")
            filters.schedule.isNotBlank() -> GET("$baseUrl/Schedule#${filters.schedule}")
            else -> GET("$baseUrl/AdvanceSearch/?name=$query&status=${filters.status}&genre=${filters.genre}&page=$page", headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.startsWith("/Schedule")) {
            val document = response.asJsoup()
            val name = response.request.url.encodedFragment!!

            val animes = document.select("div.barContent > div.schedule_container > div.schedule_item:has(div.schedule_block_title:contains($name)) div.schedule_row > div.schedule_block").map {
                SAnime.create().apply {
                    title = it.selectFirst("h2 > a > span.jtitle")!!.text()
                    thumbnail_url = it.selectFirst("img")!!.attr("src")
                    setUrlWithoutDomain(it.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
                }
            }

            AnimesPage(animes, false)
        } else {
            super.searchAnimeParse(response)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = KissAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val rating = document.selectFirst("div.Votes > div.Prct > div[data-percent]")?.let { "\n\nUser rating: ${it.attr("data-percent")}%" } ?: ""
        return SAnime.create().apply {
            title = document.selectFirst("div.barContent > div.full > h2")!!.text()
            thumbnail_url = document.selectFirst("div.cover_anime img")!!.attr("src")
            status = parseStatus(document.selectFirst("div.full > div.static_single > p:has(span:contains(Status))")!!.ownText())
            description = (document.selectFirst("div.full > div.summary > p")?.text() ?: "") + rating
            genre = document.select("div.full > p.info:has(span:contains(Genre)) > a").joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.listing > div:not([class])"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = element.selectFirst("a")!!.text()
            episode_number = element.selectFirst("a")!!.text().substringAfter("Episode ").toFloatOrNull() ?: 0F
            date_upload = parseDate(element.selectFirst("div:not(:has(a))")!!.text())
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        }
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = mutableListOf<Video>()
        val serverList = mutableListOf<Server>()

        // GET VIDEO HOSTERS
        val episodeId = (baseUrl + episode.url).toHttpUrl().queryParameter("id")!!

        var document = client.newCall(
            GET(baseUrl + episode.url, headers = headers),
        ).execute().asJsoup()
        var newDocument = document

        for (server in document.select("select#selectServer > option")) {
            val url = baseUrl + server.attr("value")

            if (!server.hasAttr("selected")) {
                newDocument = client.newCall(
                    GET(url, headers = headers),
                ).execute().asJsoup()
            }

            val ctk = newDocument.selectFirst("script:containsData(ctk)")!!.data().substringAfter("var ctk = '").substringBefore("';")

            val getIframeHeaders = Headers.headersOf(
                "Accept", "application/json, text/javascript, */*; q=0.01",
                "Alt-Used", baseUrl.toHttpUrl().host,
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
                "Host", baseUrl.toHttpUrl().host,
                "Origin", baseUrl,
                "Referer", url,
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "X-Requested-With", "XMLHttpRequest",
            )

            val getIframeBody = "episode_id=$episodeId&ctk=$ctk".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val serverName = url.toHttpUrl().queryParameter("s")!!

            val iframe = json.decodeFromString<IframeResponse>(
                client.newCall(
                    POST("$baseUrl/ajax/anime/load_episodes_v2?s=$serverName", body = getIframeBody, headers = getIframeHeaders),
                ).execute().body.string(),
            )
            var iframeUrl = Jsoup.parse(iframe.value).selectFirst("iframe")!!.attr("src")

            val password = if (iframe.value.contains("password: ")) {
                iframe.value.substringAfter("password: ").substringBefore(" <button")
            } else {
                null
            }

            if (!iframeUrl.startsWith("http")) iframeUrl = "https:$iframeUrl"
            serverList.add(Server(server.text(), iframeUrl, password))
        }

        // GET VIDEO URLS
        videoList.addAll(
            serverList.parallelMap { server ->
                runCatching {
                    val url = server.url

                    when {
                        url.contains("fembed") ||
                            url.contains("anime789.com") || url.contains("24hd.club") || url.contains("fembad.org") ||
                            url.contains("vcdn.io") || url.contains("sharinglink.club") || url.contains("moviemaniac.org") ||
                            url.contains("votrefiles.club") || url.contains("femoload.xyz") || url.contains("albavido.xyz") ||
                            url.contains("feurl.com") || url.contains("dailyplanet.pw") || url.contains("ncdnstm.com") ||
                            url.contains("jplayer.net") || url.contains("xstreamcdn.com") || url.contains("fembed-hd.com") ||
                            url.contains("gcloud.live") || url.contains("vcdnplay.com") || url.contains("superplayxyz.club") ||
                            url.contains("vidohd.com") || url.contains("vidsource.me") || url.contains("cinegrabber.com") ||
                            url.contains("votrefile.xyz") || url.contains("zidiplay.com") || url.contains("ndrama.xyz") ||
                            url.contains("fcdn.stream") || url.contains("mediashore.org") || url.contains("suzihaza.com") ||
                            url.contains("there.to") || url.contains("femax20.com") || url.contains("javstream.top") ||
                            url.contains("viplayer.cc") || url.contains("sexhd.co") || url.contains("fembed.net") ||
                            url.contains("mrdhan.com") || url.contains("votrefilms.xyz") || // url.contains("") ||
                            url.contains("embedsito.com") || url.contains("dutrag.com") || // url.contains("") ||
                            url.contains("youvideos.ru") || url.contains("streamm4u.club") || // url.contains("") ||
                            url.contains("moviepl.xyz") || url.contains("asianclub.tv") || // url.contains("") ||
                            url.contains("vidcloud.fun") || url.contains("fplayer.info") || // url.contains("") ||
                            url.contains("diasfem.com") || url.contains("javpoll.com") || url.contains("reeoov.tube") ||
                            url.contains("suzihaza.com") || url.contains("ezsubz.com") || url.contains("vidsrc.xyz") ||
                            url.contains("diampokusy.com") || url.contains("diampokusy.com") || url.contains("i18n.pw") ||
                            url.contains("vanfem.com") || url.contains("fembed9hd.com") || url.contains("votrefilms.xyz") || url.contains("watchjavnow.xyz")
                        -> {
                            val newUrl = url.replace("https://www.fembed.com", "https://vanfem.com")
                            FembedExtractor(client).videosFromUrl(newUrl, prefix = "${server.name} - ")
                        }
                        url.contains("yourupload") -> {
                            YourUploadExtractor(client).videoFromUrl(url, headers = headers, name = server.name)
                        }
                        url.contains("mp4upload") -> {
                            val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                            Mp4uploadExtractor(client).getVideoFromUrl(url, headers = headers, name = server.name)
                        }
                        url.contains("embed.vodstream.xyz") -> {
                            val referer = "$baseUrl/"
                            VodstreamExtractor(client).getVideosFromUrl(url, referer = referer, prefix = "${server.name} - ")
                        }
                        url.contains("dailymotion") -> {
                            DailymotionExtractor(client).videosFromUrl(url, prefix = "${server.name} - ", password = server.password, baseUrl = baseUrl)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    data class Server(
        val name: String,
        val url: String,
        val password: String? = null,
    )

    @Serializable
    data class IframeResponse(
        val value: String,
    )

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString.trim()) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("kissanime.com.ru", "kissanime.co", "kissanime.sx", "kissanime.org.ru")
            entryValues = arrayOf("https://kissanime.com.ru", "https://kissanime.co", "https://kissanime.sx", "https://kissanime.org.ru")
            setDefaultValue("https://kissanime.com.ru")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
