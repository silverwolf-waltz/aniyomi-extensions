package eu.kanade.tachiyomi.animeextension.pl.wbijam

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.CdaPlExtractor
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.SibnetExtractor
import eu.kanade.tachiyomi.animeextension.pl.wbijam.extractors.VkExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Wbijam : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wbijam"

    override val baseUrl = "https://wbijam.pl"

    override val lang = "pl"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeSelector(): String = "button:contains(Lista anime) + div.dropdown-content > a"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.selectFirst("a")!!.attr("href")
            thumbnail_url = ""
            title = element.selectFirst("a")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "button:contains(Wychodzące) + div.dropdown-content > a"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    // button:contains(Lista anime) + div.dropdown-content > a:contains(chainsaw)

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> {
        return Observable.defer {
            try {
                client.newCall(searchAnimeRequest(page, query, filters)).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                // RxJava doesn't handle Errors, which tends to happen during global searches
                // if an old extension using non-existent classes is still around
                throw RuntimeException(e)
            }
        }
            .map { response ->
                searchAnimeParse(response, query)
            }
    }

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(searchAnimeSelector(query)).map { element ->
            searchAnimeFromElement(element)
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    private fun searchAnimeSelector(query: String): String = "button:contains(Lista anime) + div.dropdown-content > a:contains($query)"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector(): String = throw Exception("Not used")

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(anime)
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url, headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        var counter = 1

        document.select("button:not(:contains(Wychodzące)):not(:contains(Warsztat)):not(:contains(Lista anime)) + div.dropdown-content > a").forEach seasons@{ season ->
            val seasonDoc = client.newCall(
                GET(response.request.url.toString() + "/${season.attr("href")}", headers = headers),
            ).execute().asJsoup()
            seasonDoc.select("table.lista > tbody > tr").reversed().forEach { ep ->
                val episode = SEpisode.create()

                // Skip over openings and engings
                if (preferences.getBoolean("preferred_opening", true)) {
                    if (season.text().contains("Openingi", true) || season.text().contains("Endingi", true)) {
                        return@seasons
                    }
                }

                if (ep.selectFirst("td > a") == null) {
                    val (name, scanlator) = if (preferences.getBoolean("preferred_season_view", true)) {
                        Pair(
                            ep.selectFirst("td")!!.text(),
                            season.text(),
                        )
                    } else {
                        Pair(
                            "[${season.text()}] ${ep.selectFirst("td")!!.text()}",
                            null,
                        )
                    }

                    val notUploaded = ep.selectFirst("td:contains(??.??.????)") != null

                    episode.name = name
                    episode.scanlator = if (notUploaded) {
                        "(Jeszcze nie przesłane) $scanlator"
                    } else {
                        scanlator
                    }
                    episode.episode_number = counter.toFloat()
                    episode.date_upload = ep.selectFirst("td:matches(\\d+\\.\\d+\\.\\d)")?.let { parseDate(it.text()) } ?: 0L
                    val urls = ep.select("td > span[class*=link]").map {
                        "https://${response.request.url.host}/${it.className().substringBefore("_link")}-${it.attr("rel")}.html"
                    }
                    episode.url = EpisodeType(
                        "single",
                        urls,
                    ).toJsonString()
                } else {
                    val (name, scanlator) = if (preferences.getBoolean("preferred_season_view", true)) {
                        Pair(
                            ep.selectFirst("td")!!.text(),
                            "${season.text()} • ${ep.selectFirst("td:matches([a-zA-Z]+):not(:has(a))")?.text()}",
                        )
                    } else {
                        Pair(
                            "[${season.text()}] ${ep.selectFirst("td")!!.text()}",
                            ep.selectFirst("td:matches([a-zA-Z]+):not(:has(a))")?.text(),
                        )
                    }

                    val notUploaded = ep.selectFirst("td:contains(??.??.????)") != null

                    episode.name = name
                    episode.episode_number = counter.toFloat()
                    episode.date_upload = ep.selectFirst("td:matches(\\d+\\.\\d+\\.\\d)")?.let { parseDate(it.text()) } ?: 0L
                    episode.scanlator = if (notUploaded) {
                        "(Jeszcze nie przesłane) $scanlator"
                    } else {
                        scanlator
                    }

                    episode.url = EpisodeType(
                        "multi",
                        listOf("https://${response.request.url.host}/${ep.selectFirst("td a")!!.attr("href")}"),
                    ).toJsonString()
                }

                episodeList.add(episode)
                counter++
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

//    private fun episodeFromElement(element: Element, seasonName: String): SEpisode {
//        return SEpisode.create().apply {
//            name = "[$seasonName] ${element.selectFirst("td a").text()}"
//            episode_number = if (episodeName.contains("Episode ", true)) {
//                episodeName.substringAfter("Episode ").substringBefore(" ").toFloatOrNull() ?: 0F
//            } else { 0F }
//            date_upload = element.selectFirst("span.date")?.let { parseDate(it.text()) } ?: 0L
//            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
//        }
//    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val parsed = json.decodeFromString<EpisodeType>(episode.url)
        val videoList = mutableListOf<Video>()
        val serverList = mutableListOf<String>()

        parsed.url.forEach {
            val document = client.newCall(GET(it)).execute().asJsoup()

            if (parsed.type == "single") {
                serverList.add(
                    document.selectFirst("iframe")?.attr("src")
                        ?: document.selectFirst("span.odtwarzaj_vk")?.let { t -> "https://vk.com/video${t.attr("rel")}_${t.attr("id")}" } ?: "",
                )
            } else if (parsed.type == "multi") {
                document.select("table.lista > tbody > tr.lista_hover").forEach { server ->
                    val urlSpan = server.selectFirst("span[class*=link]")!!
                    val serverDoc = client.newCall(
                        GET("https://${it.toHttpUrl().host}/${urlSpan.className().substringBefore("_link")}-${urlSpan.attr("rel")}.html"),
                    ).execute().asJsoup()
                    serverList.add(
                        serverDoc.selectFirst("iframe")?.attr("src")
                            ?: serverDoc.selectFirst("span.odtwarzaj_vk")?.let { t -> "https://vk.com/video${t.attr("rel")}_${t.attr("id")}" } ?: "",
                    )
                }
            } else {}
        }

        videoList.addAll(
            serverList.parallelMap { serverUrl ->
                runCatching {
                    when {
                        serverUrl.contains("mp4upload") -> {
                            val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                            Mp4uploadExtractor(client).getVideoFromUrl(serverUrl, headers)
                        }
                        serverUrl.contains("cda.pl") -> {
                            CdaPlExtractor(client).getVideosFromUrl(serverUrl, headers)
                        }
                        serverUrl.contains("sibnet.ru") -> {
                            SibnetExtractor(client).getVideosFromUrl(serverUrl)
                        }
                        serverUrl.contains("vk.com") -> {
                            VkExtractor(client).getVideosFromUrl(serverUrl, headers)
                        }
                        serverUrl.contains("dailymotion") -> {
                            DailymotionExtractor(client).videosFromUrl(serverUrl, headers)
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

    private fun EpisodeType.toJsonString(): String {
        return json.encodeToString(this)
    }

    @Serializable
    data class EpisodeType(
        val type: String,
        val url: List<String>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "vstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferowana jakość"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferowany serwer"
            entries = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            entryValues = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            setDefaultValue("cda.pl")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val seasonViewPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_season_view"
            title = "Przenieś nazwę sezonu do skanera"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        val openEndPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_opening"
            title = "Usuń zakończenia i otwory"
            summary = "Usuń zakończenia i otwarcia z listy odcinków"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
        screen.addPreference(seasonViewPref)
        screen.addPreference(openEndPref)
    }
}
