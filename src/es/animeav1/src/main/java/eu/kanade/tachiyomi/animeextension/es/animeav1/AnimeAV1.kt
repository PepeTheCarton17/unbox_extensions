package eu.kanade.tachiyomi.animeextension.es.animeav1

import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.VoeExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.extractors.ZillaExtractor
import eu.kanade.tachiyomi.animeextension.es.animeav1.network.AnubisInterceptor
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
import java.util.ArrayList

class AnimeAV1 : ParsedAnimeHttpSource() {

    override val name = "AnimeAV1"

    override val baseUrl = "https://animeav1.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(AnubisInterceptor())
        .build()

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/catalogo?page=$page", headers)

    override fun popularAnimeSelector(): String = "article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href*=/media/]")
        val href = link?.attr("href") ?: ""
        val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
        anime.setUrlWithoutDomain(fullUrl)

        val titleEl = element.selectFirst("h3")
        val altTitle = element.selectFirst("span.sr-only")?.text()?.replace("Ver ", "")?.trim()
        anime.title = titleEl?.text()?.trim() ?: altTitle ?: "Sin título"

        val img = element.selectFirst("img")
        anime.thumbnail_url = img?.attr("src") ?: img?.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a:contains(»)"

    // ============================== Latest Updates =============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/catalogo?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search Anime ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/catalogo?search=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

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

        // 1. Intentar extracción directa de enlaces en el HTML
        val links = document.select("a[href^=/media/], a[href*=/media/]")
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

        // 1. Extraer los servidores de reproducción desde el objeto embeds de SvelteKit
        val serverMatches = Regex("""server:\s*["']([^"']+)["'],\s*url:\s*["'](https?://[^"']+)["']""").findAll(html)

        for (match in serverMatches) {
            val serverName = match.groupValues[1].trim()
            val embedUrl = match.groupValues[2].trim()

            when (serverName.lowercase()) {
                "hls", "zilla" -> {
                    runCatching {
                        videoList.addAll(ZillaExtractor(client).videosFromUrl(embedUrl, prefix = "Zilla - "))
                    }
                }
                "voe" -> {
                    runCatching {
                        videoList.addAll(VoeExtractor(client).videosFromUrl(embedUrl))
                    }
                }
                "mp4upload" -> {
                    runCatching {
                        videoList.addAll(Mp4uploadExtractor(client).getVideoFromUrl(embedUrl, headers))
                    }
                }
                "byse" -> {
                    runCatching {
                        videoList.addAll(StreamWishExtractor(client).videosFromUrl(embedUrl, videoNameHost = "Byse - "))
                    }
                }
                else -> {
                    // Servidores con enlace directo
                    if (embedUrl.endsWith(".m3u8") || embedUrl.endsWith(".mp4")) {
                        videoList.add(Video(embedUrl, serverName, embedUrl, headers = headers))
                    }
                }
            }
        }

        // 2. Extraer iframes directos como respaldo
        if (videoList.isEmpty()) {
            val document = org.jsoup.Jsoup.parse(html, response.request.url.toString())
            for (iframe in document.select("iframe[src]")) {
                val src = iframe.attr("abs:src")
                if (src.contains("player.zilla-networks.com")) {
                    runCatching { videoList.addAll(ZillaExtractor(client).videosFromUrl(src)) }
                } else if (src.contains("voe.sx")) {
                    runCatching { videoList.addAll(VoeExtractor(client).videosFromUrl(src)) }
                } else if (src.contains("mp4upload.com")) {
                    runCatching { videoList.addAll(Mp4uploadExtractor(client).getVideoFromUrl(src, headers)) }
                }
            }
        }

        return videoList
    }
}
