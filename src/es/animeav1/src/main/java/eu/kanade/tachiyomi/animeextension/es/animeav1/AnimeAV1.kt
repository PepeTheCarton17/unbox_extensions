package eu.kanade.tachiyomi.animeextension.es.animeav1

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.VoeExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.ZillaExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.network.AnubisInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.ArrayList

class AnimeAV1 : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AnimeAV1"

    override val baseUrl = "https://animeav1.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(AnubisInterceptor())
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "MP4Upload"
        private val SERVER_ENTRIES = arrayOf("MP4Upload (Recomendado)", "Voe", "Byse (StreamWish)", "HLS (Zilla)")
        private val SERVER_VALUES = arrayOf("MP4Upload", "Voe", "Byse", "Zilla")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val LANG_ENTRIES = arrayOf("Subtitulado (SUB)", "Doblado / Latino (DUB)", "Todos (Mostrar ambos)")
        private val LANG_VALUES = arrayOf("SUB", "DUB", "ALL")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeSelector(): String = "article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/media/]")
        val href = link?.attr("href") ?: ""
        // Si el enlace apunta a un episodio /media/{slug}/{num}, convertir a /media/{slug}
        val cleanHref = if (href.matches(Regex(""".+/media/[^/]+/\d+$"""))) {
            href.substringBeforeLast("/")
        } else {
            href
        }
        val fullUrl = if (cleanHref.startsWith("http")) cleanHref else "$baseUrl$cleanHref"
        anime.setUrlWithoutDomain(fullUrl)

        val titleEl = element.selectFirst("h3")
            ?: element.selectFirst("div[class*=text-2xs]")
            ?: element.selectFirst("header div")
        val altTitle = element.selectFirst("span.sr-only")?.text()?.replace("Ver ", "")?.trim()
        anime.title = titleEl?.text()?.trim() ?: altTitle ?: "Sin título"

        val img = element.selectFirst("img")
        anime.thumbnail_url = img?.attr("src") ?: img?.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a:contains(»)"

    // ============================== Latest Updates =============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search Anime ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val path = AnimeAV1Filters.buildQuery(page, query, filters)
        return GET("$baseUrl$path", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun getFilterList(): AnimeFilterList = AnimeAV1Filters.getFilterList()

    // ============================== Anime Details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1")?.text()?.trim() ?: ""
        anime.description = document.selectFirst("p")?.text()?.trim()

        val genres = document.select("a[href*=/catalogo?genre=]").map { it.text().trim() }
        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }

        val coverImg = document.selectFirst("img[src*=/covers/]") ?: document.selectFirst("img[src*=/backdrops/]")
        anime.thumbnail_url = coverImg?.attr("src")

        val pageText = document.text()
        anime.status = when {
            pageText.contains("Finalizado", ignoreCase = true) -> SAnime.COMPLETED
            pageText.contains("En emisión", ignoreCase = true) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        return anime
    }

    // =============================== Episode List ==============================

    override fun episodeListSelector(): String = "throw UnsupportedOperationException"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = ArrayList<SEpisode>()

        // 1. Extracción directa de enlaces en el HTML
        val links = document.select("a[href^=/media/], a[href*=/media/]")
            .toList()
            .filter { it.attr("href").matches(Regex(""".+/media/[^/]+/\d+$""")) }

        if (links.isNotEmpty()) {
            for (link in links.reversed()) {
                val href = link.attr("href")
                val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 1f
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    name = "Episodio ${epNum.toInt()}"
                    episode_number = epNum
                }
                episodeList.add(episode)
            }
            return episodeList.distinctBy { it.episode_number }
        }

        // 2. Extracción de respaldo desde el estado hidratado de SvelteKit
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.data()
            if (content.contains("episodes:[") || content.contains("episodesCount")) {
                val matches = Regex("""\{id:\s*(\d+),\s*number:\s*(\d+)\}""").findAll(content)
                val currentSlug = response.request.url.encodedPath.substringAfterLast("/media/").substringBefore("/")
                for (m in matches) {
                    val epNum = m.groupValues[2].toFloatOrNull() ?: continue
                    val episode = SEpisode.create().apply {
                        setUrlWithoutDomain("/media/$currentSlug/${epNum.toInt()}")
                        name = "Episodio ${epNum.toInt()}"
                        episode_number = epNum
                    }
                    episodeList.add(episode)
                }
                if (episodeList.isNotEmpty()) {
                    return episodeList.reversed()
                }
            }
        }

        return episodeList
    }

    // =============================== Video List ===============================

    override fun videoListSelector(): String = "throw UnsupportedOperationException"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val videoList = mutableListOf<Video>()

        // Extraer bloques independientes SUB y DUB (o LAT)
        val dubBlock = Regex("""DUB\s*:\s*\[([^]]*)]""").find(html)?.groupValues?.get(1)
            ?: Regex("""LAT\s*:\s*\[([^]]*)]""").find(html)?.groupValues?.get(1)
        val subBlock = Regex("""SUB\s*:\s*\[([^]]*)]""").find(html)?.groupValues?.get(1)

        val serverRegex = Regex("""\{\s*server\s*:\s*["']([^"']+)["']\s*,\s*url\s*:\s*["'](https?://[^"']+)["']\s*\}""")

        // 1. Procesar servidores con audio Doblado / Latino (DUB)
        if (!dubBlock.isNullOrEmpty()) {
            for (match in serverRegex.findAll(dubBlock)) {
                val serverName = match.groupValues[1].trim()
                val embedUrl = match.groupValues[2].trim()
                extractVideosForServer(videoList, serverName, embedUrl, prefix = "[DUB] ")
            }
        }

        // 2. Procesar servidores Subtitulados (SUB)
        if (!subBlock.isNullOrEmpty()) {
            for (match in serverRegex.findAll(subBlock)) {
                val serverName = match.groupValues[1].trim()
                val embedUrl = match.groupValues[2].trim()
                extractVideosForServer(videoList, serverName, embedUrl, prefix = "[SUB] ")
            }
        }

        // 3. Respaldo general si los bloques no estaban separados
        if (videoList.isEmpty()) {
            val generalMatches = Regex("""server:\s*["']([^"']+)["'],\s*url:\s*["'](https?://[^"']+)["']""").findAll(html)
            for (match in generalMatches) {
                val serverName = match.groupValues[1].trim()
                val embedUrl = match.groupValues[2].trim()
                extractVideosForServer(videoList, serverName, embedUrl, prefix = "[SUB] ")
            }
        }

        return videoList.sortVideos()
    }

    private fun extractVideosForServer(videoList: MutableList<Video>, serverName: String, embedUrl: String, prefix: String) {
        when (serverName.lowercase()) {
            "mp4upload" -> {
                runCatching {
                    videoList.addAll(Mp4uploadExtractor(client).getVideoFromUrl(embedUrl, headers, prefix = "${prefix}MP4Upload - "))
                }
            }
            "voe" -> {
                runCatching {
                    videoList.addAll(VoeExtractor(client).videosFromUrl(embedUrl, prefix = "${prefix}Voe - "))
                }
            }
            "byse" -> {
                runCatching {
                    videoList.addAll(StreamWishExtractor(client).videosFromUrl(embedUrl, videoNameHost = "${prefix}Byse - "))
                }
            }
            "hls", "zilla" -> {
                runCatching {
                    videoList.addAll(ZillaExtractor(client).videosFromUrl(embedUrl, prefix = "${prefix}Zilla - "))
                }
            }
            else -> {
                if (embedUrl.endsWith(".m3u8") || embedUrl.endsWith(".mp4")) {
                    videoList.add(Video(embedUrl, "${prefix}$serverName", embedUrl, headers = headers))
                }
            }
        }
    }

    // ============================ Preferences & Sorting ============================

    private fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return this.sortedWith(
            compareBy(
                { if (prefLang == "ALL") 0 else if (it.quality.contains(prefLang, ignoreCase = true)) 1 else 0 },
                { if (it.quality.contains(prefServer, ignoreCase = true)) 1 else 0 },
                { if (it.quality.contains(prefQuality, ignoreCase = true)) 1 else 0 }
            )
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor preferido"
            entries = SERVER_ENTRIES
            entryValues = SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Idioma / Tipo de audio preferido"
            entries = LANG_ENTRIES
            entryValues = LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Calidad preferida"
            entries = QUALITY_ENTRIES
            entryValues = QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }
}
