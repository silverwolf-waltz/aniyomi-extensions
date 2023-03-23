package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.kisskh.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KissKH : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "KissKH"

    override val baseUrl = "https://kisskh.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]!!.jsonPrimitive.int
        val page = jObject["page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/api/DramaList/Drama/$animeId?isq=false")
            anime.thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content ?: ""
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        return parseEpisodePage(responseString)
    }

    private fun parseEpisodePage(jsonLine: String?): List<SEpisode> {
        val jsonData = jsonLine ?: return mutableListOf()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        val array = jObject["episodes"]!!.jsonArray
        val type = jObject["type"]!!.jsonPrimitive.content
        val episodesCount = jObject["episodesCount"]!!.jsonPrimitive.int
        for (item in array) {
            val episode = SEpisode.create()
            val id = item.jsonObject["id"]!!.jsonPrimitive.content
            episode.episode_number = item.jsonObject["number"]!!.jsonPrimitive.float
            val number = item.jsonObject["number"]!!.jsonPrimitive.content.replace(".0", "")
            when {
                type.contains("Anime") || type.contains("TVSeries") -> {
                    episode.name = "Episode $number"
                }
                type.contains("Hollywood") && episodesCount == 1 || type.contains("Movie") -> {
                    episode.name = "Movie"
                }
                type.contains("Hollywood") && episodesCount > 1 -> {
                    episode.name = "Episode $number"
                }
            }
            episode.setUrlWithoutDomain("$baseUrl/api/DramaList/Episode/$id.png?err=false&ts=&time=")
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val id = response.request.url.toString()
            .substringAfter("Episode/").substringBefore(".png")
        return videosFromElement(response, id)
    }

    private fun videosFromElement(response: Response, id: String): List<Video> {
        val jsonData = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val videoList = mutableListOf<Video>()
        val subData = client.newCall(GET("$baseUrl/api/Sub/$id")).execute().body.string()
        val subj = json.decodeFromString<JsonArray>(subData)
        val subList = mutableListOf<Track>()
        for (item in subj) {
            try {
                val suburl = item.jsonObject["src"]!!.jsonPrimitive.content
                val lang = item.jsonObject["label"]!!.jsonPrimitive.content
                subList.add(Track(suburl, lang))
            } catch (_: Error) {}
        }
        val videoUrl = jObject["Video"]!!.jsonPrimitive.content
        videoList.add(Video(videoUrl, "FirstParty", videoUrl, subtitleTracks = subList, headers = Headers.headersOf("referer", "https://kisskh.me/", "origin", "https://kisskh.me")))
        val thridpartyurl = jObject["ThirdParty"]!!.jsonPrimitive.content

        val video = StreamSBExtractor(client).videosFromUrl(thridpartyurl, headers, common = true)
        videoList.addAll(video)
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        if (hoster != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(hoster)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api/DramaList/Search?q=$query&type=0")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val array = json.decodeFromString<JsonArray>(jsonData)
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/api/DramaList/Drama/$animeId?isq=false")
            anime.thumbnail_url = item.jsonObject["thumbnail"]!!.jsonPrimitive.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    // Details

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        return parseAnimeDetailsParseJson(responseString)
    }

    private fun parseAnimeDetailsParseJson(jsonLine: String?): SAnime {
        val anime = SAnime.create()
        val jsonData = jsonLine ?: return anime
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        anime.title = jObject.jsonObject["title"]!!.jsonPrimitive.content
        anime.status = parseStatus(jObject.jsonObject["status"]!!.jsonPrimitive.content)
        anime.description = jObject.jsonObject["description"]!!.jsonPrimitive.content
        anime.thumbnail_url = jObject.jsonObject["thumbnail"]!!.jsonPrimitive.content

        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=2&pageSize=40")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseLatestAnimeJson(responseString)
    }

    private fun parseLatestAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]!!.jsonPrimitive.int
        val page = jObject["page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/api/DramaList/Drama/$animeId?isq=false")
            anime.thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content ?: ""
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("StreamSB", "FirstParty")
            entryValues = arrayOf("https://streamsss.net", "FirstParty")
            setDefaultValue("FirstParty")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(hosterPref)
    }
}
