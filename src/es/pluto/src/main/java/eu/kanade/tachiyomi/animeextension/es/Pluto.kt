package eu.kanade.tachiyomi.animeextension.es.pluto

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Pluto : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Pluto"

    override val baseUrl = "https://pluto.tv"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    // Rutas detectadas durante el análisis (vacías si no se encontraron).
    private val listingPath = "/latam/category/0fc98d49-648f-4d89-9c44-95e61107175f/"
    private val latestPath = ""
    private val searchPath = ""
    private val searchParam = ""

    private fun pageUrl(path: String, page: Int): String {
        val clean = if (path.isEmpty()) "/" else if (path.startsWith("/")) path else "/$path"
        val base = "$baseUrl$clean"
        return when ("none") {
            "path" -> if (page <= 1) base else "$baseUrl${clean.trimEnd('/')}/page/$page/"
            "query" -> if (page <= 1) base
                else "$base${if (base.contains("?")) "&" else "?"}page=$page"
            else -> base
        }
    }

    private fun searchUrl(query: String, page: Int): String {
        val path = if (searchPath.isEmpty()) "/" else if (searchPath.startsWith("/")) searchPath else "/$searchPath"
        if (searchPath.isEmpty() || searchParam.isEmpty()) {
            return pageUrl(listingPath, page)
        }
        val base = "$baseUrl$path"
        val sep = if (base.contains("?")) "&" else "?"
        val encoded = Uri.encode(query)
        val first = "$base$sep$searchParam=$encoded"
        return if (page <= 1) first else "$first&page=$page"
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== POPULAR ANIME ==============================
    override fun popularAnimeRequest(page: Int): Request = GET(pageUrl(listingPath, page), headers)

    override fun popularAnimeSelector(): String = "a.TileLink_tileLink__pIMWG"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        // En muchas portadas la tarjeta ES el enlace (`a.card`): `selectFirst`
        // solo busca descendientes, así que hay que aceptar el propio nodo.
        val link = if (element.tagName() == "a") element else element.selectFirst("a")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        title = element.selectFirst("img")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: element.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Sin Título"
        thumbnail_url = element.selectFirst("img")
            ?.let { img ->
                img.attr("abs:src")
                    .ifBlank { img.attr("abs:data-src") }
                    .ifBlank { img.attr("abs:data-lazy-src") }
            }
            ?.takeIf { it.isNotEmpty() }
    }

    override fun popularAnimeNextPageSelector(): String? = "".ifBlank { null }

    override fun popularAnimeParse(response: Response): AnimesPage =
        filterBlacklistedAnimes(super.popularAnimeParse(response))

    // ============================== LATEST UPDATES ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET(pageUrl(latestPath, page), headers)

    override fun latestUpdatesSelector(): String = "a.TileLink_tileLink__pIMWG"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = "".ifBlank { null }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        filterBlacklistedAnimes(super.latestUpdatesParse(response))

    // ============================== SEARCH ANIME ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        val audioFilter = filters.filterIsInstance<PlutoFilters.AudioFilter>().firstOrNull()
        val isDubSelected = audioFilter?.let { it.vals[it.state].second == "dub" } ?: false

        val finalQuery = when {
            isDubSelected && cleanQuery.isNotBlank() -> "$cleanQuery latino"
            isDubSelected -> "latino"
            else -> cleanQuery
        }

        val url = if (finalQuery.isNotBlank()) {
            searchUrl(finalQuery, page)
        } else {
            pageUrl(listingPath, page)
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = "a.TileLink_tileLink__pIMWG"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = "".ifBlank { null }

    override fun searchAnimeParse(response: Response): AnimesPage =
        filterBlacklistedAnimes(super.searchAnimeParse(response))

    private fun filterBlacklistedAnimes(page: AnimesPage): AnimesPage {
        val rawBlacklist = preferences.getString("genre_blacklist_text", "") ?: ""
        val blacklistWords = rawBlacklist
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (blacklistWords.isEmpty()) return page

        val filtered = page.animes.filterNot { anime ->
            val genreLower = anime.genre?.lowercase() ?: ""
            val titleLower = anime.title.lowercase()
            blacklistWords.any { word ->
                (genreLower.isNotEmpty() && genreLower.contains(word)) || titleLower.contains(word)
            }
        }
        return AnimesPage(filtered, page.hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = PlutoFilters.getFilterList()

    // ============================== DETAILS ==============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1, .title, .entry-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Anime"
        description = document.selectFirst("p.sinopsis, div.description, .entry-content p")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
        genre = document.select("a[href*=/genero/], a[href*=/genre/], .genres a").joinToString(", ") { it.text() }
        status = SAnime.UNKNOWN
        thumbnail_url = document.selectFirst("img.poster, .poster img")?.attr("abs:src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("article img, main img")?.attr("abs:src")
    }

    // ============================== EPISODES ==============================
    override fun episodeListSelector(): String = "a[href*='/episode/']"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        // La tarjeta puede ser el propio enlace.
        val link = if (element.tagName() == "a") element else element.selectFirst("a")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        // Nombre: un título explícito o los dos primeros textos cortos. En
        // tarjetas como las de Pluto TV el primero es "T1 E1" y el segundo el
        // título del episodio; así no se cuela la sinopsis completa.
        val explicitTitle = element.selectFirst(".title, [class*=episodeTitle], h3, h4")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val shortTexts = element.select("span")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length <= 60 }
            .take(2)
            .joinToString(" ")
        name = explicitTitle
            ?: shortTexts.takeIf { it.isNotEmpty() }
            ?: link?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Episodio"
        val seasonEpisode = Regex("""[TS]\s*\d+\s*[Ee]\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
        val wordEpisode = Regex("""(?:episodio|capitulo|capítulo|ep)\s*[-#:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)
        episode_number = (seasonEpisode?.groupValues?.get(1) ?: wordEpisode?.groupValues?.get(1))
            ?.toFloatOrNull() ?: 1f
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== VIDEO LIST & EXTRACTORS ==============================

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // `(?![a-zA-Z])` evita falsos positivos como `300px`/`250px` del CSS.
    private val resolutionRegex = Regex("""(\d{3,4})p(?![a-zA-Z])""", RegexOption.IGNORE_CASE)

    private fun normalizeQuality(raw: String?): String? {
        val height = raw?.toIntOrNull() ?: return null
        return if (height in 144..4320) height.toString() + "p" else null
    }

    /**
     * Mapa url -> audio ("SUB"/"DUB") leyendo los bloques `SUB:[{server:...,url:...}]`
     * del payload. Sin esto, un iframe del reproductor por defecto puede etiquetarse
     * como DUB solo porque la pestaña DUB del DOM aparece mas cerca en el HTML.
     */
    private fun audioBlocks(html: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairRegex = Regex("""server\s*:\\?\s*["']([^"']+)["']\s*,\s*url\s*:\s*\\?\s*["'](https?://[^"']+)["']""")
        val labels = listOf("SUB" to "SUB", "DUB" to "DUB", "LAT" to "DUB", "CAST" to "DUB")
        for ((label, audio) in labels) {
            val blockRegex = Regex("""$label\s*:\s*\[([^\]]*)]""")
            for (block in blockRegex.findAll(html)) {
                for (match in pairRegex.findAll(block.groupValues[1])) {
                    map[match.groupValues[2].substringBefore("?")] = audio
                }
            }
        }
        return map
    }

    override fun videoListParse(response: Response): List<Video> {
        // Pluto TV no expone .m3u8/iframe en el HTML estático: el stream se
        // resuelve mediante GraphQL (verificado 2026-11-17). El cuerpo aún no
        // se ha consumido, así que podemos pasarlo al extractor de Pluto.
        if (baseUrl.contains("pluto.tv") || response.request.url.toString().contains("pluto.tv")) {
            return plutoVideos(response)
        }

        val html = response.body?.string().orEmpty()
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val blacklist = preferences.getStringSet("server_blacklist", emptySet()) ?: emptySet()

        val embeds = linkedSetOf<Pair<String, String>>()

        document.select("iframe[src], iframe[data-src]").forEach { el ->
            val src = el.attr("abs:src").ifBlank { el.attr("abs:data-src") }
            if (src.startsWith("http")) embeds.add(el.attr("data-server").ifBlank { "" } to src)
        }

        document.select("video[src], video source[src]").forEach { el ->
            val src = el.attr("abs:src")
            if (src.startsWith("http")) embeds.add("" to src)
        }

        val dataAttrs = listOf("data-video", "data-url", "data-embed", "data-src")
        document.select("[data-video], [data-url], [data-embed]").forEach { el ->
            val src = dataAttrs.firstNotNullOfOrNull { name ->
                el.attr(name).takeIf { it.isNotBlank() }
            } ?: return@forEach
            val absolute = absolutize(baseUrl, src)
            if (absolute.startsWith("http")) {
                embeds.add(el.attr("data-server").ifBlank { el.text().trim() } to absolute)
            }
        }

        val serverPatterns = listOf(
            Regex("""server\s*:\s*["']([^"']+)["']\s*,\s*url\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""["']server["']\s*:\s*["']([^"']+)["']\s*,\s*["']url["']\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""(?:server|host|name)\s*:\s*["']([^"']+)["'][^{}]{0,160}?(?:url|file|embed|link)\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""["'](?:server|host|name)["']\s*:\s*["']([^"']+)["'][^{}]{0,160}?["'](?:url|file|embed|link)["']\s*:\s*["'](https?://[^"']+)["']""")
        )
        for (pattern in serverPatterns) {
            for (match in pattern.findAll(html)) {
                embeds.add(match.groupValues[1] to match.groupValues[2])
            }
        }

        // Playlists HLS embebidas directamente en el HTML (payload tipo SvelteKit / JSON).
        val rawPlaylists = Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
        for (match in rawPlaylists.findAll(html)) {
            embeds.add("" to match.groupValues[1].replace("\\/", "/"))
        }

        val seen = mutableSetOf<String>()
        val audioMap = audioBlocks(html)
        for ((declared, embedUrl) in embeds) {
            val key = embedUrl.substringBefore("?").lowercase()
            if (!seen.add(key)) continue

            val server = declared.ifBlank { serverFromUrl(embedUrl) }
            if (isBlacklisted(blacklist, server, embedUrl)) continue

            val audio = audioMap[embedUrl.substringBefore("?")] ?: detectAudio(html, embedUrl)
            runCatching {
                videos.addAll(resolveVideos(server, embedUrl, audio, headers))
            }
        }

        return sortVideos(videos)
    }

    // ---- Pluto TV: flujo de reproducción verificado (LATAM, 2026-11-17) ----
    // Las páginas de episodio no contienen .m3u8 ni iframe: el stream se
    // resuelve con GraphQL. PtvStart entrega un JWT y la cookie CBS_COM;
    // StreamingUrl (con esa cookie) entrega la ruta del máster HLS; el máster
    // se descarga con Authorization: Bearer <jwt>. Las variantes ya llevan el
    // jwt en la query, así que el reproductor no necesita reenviar el header.
    private val plutoGql = "https://pluto.tv/api/tn/app-shell/graphql/"
    private val plutoPlayout = "https://ipv4.pluto.tv/api/tn/video/playout/"
    private val plutoHashPtvStart = "1f49cc7143a332451387d2145bcbddca135fdc970042f748d3e423e484ec4ecc"
    private val plutoHashStreaming = "d2210e84a51ed4a4382c15720a7366ee955d6354ee76039f58a34ecf274134b7"

    private fun plutoGqlGet(operation: String, hash: String, variables: String, source: String, cookie: String? = null): Response {
        val url = plutoGql.toHttpUrl().newBuilder().apply {
            addQueryParameter("operationName", operation)
            addQueryParameter("variables", variables)
            addQueryParameter("extensions", "{\"tnPersistedDocumentHash\":\"$hash\"}")
        }.build()
        val h = headers.newBuilder()
            .add("Accept", "application/json")
            .add("apollo-require-preflight", "true")
            .add("x-apollo-operation-name", operation)
            .add("request-source", source)
        if (cookie != null) h.add("Cookie", cookie)
        return client.newCall(Request.Builder().url(url).headers(h.build()).get().build()).execute()
    }

    private fun plutoVideos(response: Response): List<Video> {
        val finalUrl = response.request.url.toString()
        val contentId = Regex("""/episode/([0-9a-f]{24})""").find(finalUrl)
            ?: throw Exception("No se pudo extraer el contentId de Pluto TV")

        // 1) PtvStart sin cookies -> JWT + CBS_COM (viene en la cabecera).
        val (jwt, cbsCookie) = plutoGqlGet(
            "PtvStart", plutoHashPtvStart, "{\"params\":{}}", "ptvStart"
        ).use { resp ->
            if (!resp.isSuccessful) throw Exception("Pluto PtvStart HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val token = Regex("\"jwt\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: throw Exception("Pluto PtvStart no devolvió JWT")
            val cbs = Regex("""CBS_COM=([^;]+)""")
                .find(resp.header("x-external-set-cookie") ?: "")?.groupValues?.get(1)
                ?: throw Exception("Pluto PtvStart no devolvió CBS_COM")
            token to "CBS_COM=$cbs"
        }

        // 2) StreamingUrl con la cookie CBS_COM -> ruta del máster.
        val path = plutoGqlGet(
            "StreamingUrl", plutoHashStreaming,
            "{\"params\":{\"appVersion\":\"10.12.1074\",\"contentId\":\"${contentId.groupValues[1]}\",\"contentType\":\"vod\",\"drm\":\"\",\"drmLevel\":\"\",\"streamType\":\"stitcher\"}}",
            "streaming-url-request", cbsCookie
        ).use { resp ->
            if (!resp.isSuccessful) throw Exception("Pluto StreamingUrl HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (!Regex("\"success\"\\s*:\\s*true").containsMatchIn(body))
                throw Exception("Pluto StreamingUrl devolvió success=false")
            Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: throw Exception("Pluto StreamingUrl sin stitcherPaths")
        }

        // 3) Máster HLS con Bearer. Las variantes se parsean a mano (sin
        //    PlaylistUtils, que no está en extensions-lib) y llevan ?jwt=...
        val masterUrl = plutoPlayout + path.trimStart('/')
        val masterHeaders = headers.newBuilder().add("Authorization", "Bearer $jwt").build()
        val master = client.newCall(Request.Builder().url(masterUrl).headers(masterHeaders).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Pluto máster HLS HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }

        val videos = mutableListOf<Video>()
        val lines = master.lines()
        var bandwidth = ""
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXT-X-STREAM-INF")) {
                bandwidth = Regex("""BANDWIDTH=(\d+)""").find(t)?.groupValues?.get(1) ?: ""
            } else if (t.isNotBlank() && !t.startsWith("#") && bandwidth.isNotBlank()) {
                val variantUrl = absolutize(masterUrl, t)
                val kbps = bandwidth.toIntOrNull()?.let { it / 1000 } ?: 0
                videos.add(Video(variantUrl, "Pluto TV - ${kbps} kbps", "${kbps} kbps", masterHeaders))
                bandwidth = ""
            }
        }
        if (videos.isEmpty()) throw Exception("Pluto máster HLS sin variantes")
        return sortVideos(videos)
    }

    private fun serverFromUrl(url: String): String {
        val host = url.substringAfter("://", url).substringBefore("/").lowercase()
        return when {
            host.contains("mp4upload") -> "MP4Upload"
            host.contains("voe") -> "Voe"
            host.contains("streamwish") || host.contains("byse") || host.contains("swhoi") -> "Byse"
            host.contains("zilla") -> "Zilla"
            host.contains("mega") -> "Mega"
            host.contains("upnshare") -> "UPNShare"
            host.contains("filemoon") -> "Filemoon"
            host.contains("dood") -> "Dood"
            else -> "Direct"
        }
    }

    private fun isBlacklisted(blacklist: Set<String>, server: String, url: String): Boolean {
        if (blacklist.isEmpty()) return false
        val s = server.lowercase()
        val u = url.lowercase()
        return blacklist.any { raw ->
            val key = raw.lowercase()
            key.isNotEmpty() && (
                s.contains(key) || u.contains(key) ||
                    (key == "zilla" && (s.contains("hls") || u.contains("zilla"))) ||
                    (key == "byse" && (s.contains("streamwish") || u.contains("streamwish"))) ||
                    (key == "direct" && s.contains("direct"))
                )
        }
    }

    private fun detectAudio(html: String, url: String): String {
        val idx = html.indexOf(url)
        if (idx <= 0) return "SUB"
        val before = html.substring(0, idx).uppercase()
        val dubAt = maxOf(
            before.lastIndexOf("DUB"),
            before.lastIndexOf("LAT"),
            before.lastIndexOf("CAST"),
            before.lastIndexOf("AUDIO LATINO")
        )
        val subAt = before.lastIndexOf("SUB")
        return if (dubAt > subAt) "DUB" else "SUB"
    }

    private fun resolveVideos(server: String, url: String, audio: String, baseHeaders: Headers): List<Video> {
        val prefix = "[$audio] "
        val s = server.lowercase()
        val u = url.lowercase()
        return when {
            s.contains("mp4upload") || u.contains("mp4upload") ->
                mp4uploadVideos(url, prefix + "MP4Upload - ", baseHeaders)
            s.contains("voe") || u.contains("voe.sx") ->
                voeVideos(url, prefix + "Voe - ", baseHeaders)
            s.contains("byse") || s.contains("streamwish") || u.contains("streamwish") || u.contains("byse") ->
                streamWishVideos(url, prefix + "Byse - ", baseHeaders)
            s.contains("zilla") || u.contains("zilla-networks") ->
                zillaVideos(url, prefix + "Zilla - ")
            u.contains("mega.nz") || u.contains("mega.co.nz") || s.contains("mega") -> emptyList()
            u.contains(".m3u8") -> hlsVideos(url, prefix, baseHeaders)
            u.contains(".mp4") -> listOf(Video(url, prefix + "MP4", url, baseHeaders))
            else -> genericVideos(url, prefix + server + " - ", baseHeaders)
        }
    }

    private fun embedHeaders(url: String, origin: String, baseHeaders: Headers): Headers =
        baseHeaders.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .set("Accept", "*/*")
            .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .build()

    private fun fetchText(url: String, extra: Headers): String = runCatching {
        val request = Request.Builder().url(url).headers(extra).build()
        client.newCall(request).execute().use { response -> response.body?.string().orEmpty() }
    }.getOrDefault("")

    private fun absolutize(base: String, ref: String): String {
        val clean = ref.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("//")) return "https:" + clean
        val origin = originOf(base)
        if (clean.startsWith("/")) return origin + clean
        return base.substringBeforeLast('/', base) + "/" + clean
    }

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val hostEnd = url.indexOf('/', schemeEnd + 3)
        return if (hostEnd < 0) url else url.substring(0, hostEnd)
    }

    private fun isMediaUrl(url: String): Boolean =
        url.contains(".mp4") || url.contains(".m3u8")

    private fun qualityFromUrlOnly(url: String): String =
        normalizeQuality(resolutionRegex.find(url)?.groupValues?.get(1)) ?: "Auto"

    private fun qualityFromBody(url: String, body: String): String {
        normalizeQuality(resolutionRegex.find(url)?.groupValues?.get(1))?.let { return it }
        normalizeQuality(resolutionRegex.find(body)?.groupValues?.get(1))?.let { return it }
        return "Auto"
    }

    private fun distinctVideos(videos: List<Video>): List<Video> {
        val seen = mutableSetOf<String>()
        // `videoUrl` es nullable en extensions-lib: sin `?` esto no compila.
        return videos.filter { seen.add(it.videoUrl?.substringBefore("?") ?: "") }
    }

    private fun mp4uploadVideos(url: String, prefix: String, baseHeaders: Headers): List<Video> {
        val videoHeaders = embedHeaders(url, "https://www.mp4upload.com", baseHeaders)
        val body = fetchText(url, videoHeaders)
        if (body.isBlank()) return emptyList()

        val srcRegex = Regex("""["']?(?:src|file|stream)["']?\s*[:=]\s*["']([^"']+)["']""")
        val labelRegex = Regex("""["']?(?:label|quality|res|resolution|size)["']?\s*[:=]\s*["']?(\d{3,4})p?(?![a-zA-Z])""")
        val objectRegex = Regex("""\{[^{}]*\}""")

        val videos = mutableListOf<Video>()
        for (obj in objectRegex.findAll(body)) {
            val chunk = obj.value
            val raw = srcRegex.find(chunk)?.groupValues?.get(1) ?: continue
            if (!isMediaUrl(raw)) continue
            val absolute = absolutize(url, raw)
            val label = normalizeQuality(labelRegex.find(chunk)?.groupValues?.get(1))
                ?: qualityFromBody(absolute, chunk)
            videos.add(Video(absolute, prefix + label, absolute, videoHeaders))
        }

        if (videos.isEmpty()) {
            val raw = srcRegex.findAll(body).map { it.groupValues[1] }.firstOrNull { isMediaUrl(it) }
            if (raw != null) {
                val absolute = absolutize(url, raw)
                videos.add(Video(absolute, prefix + qualityFromBody(absolute, body), absolute, videoHeaders))
            }
        }

        return distinctVideos(videos)
    }

    private fun isDecoyUrl(url: String): Boolean =
        url.contains("test-videos.co.uk") || url.contains("bigbuckbunny")

    private fun voeVideos(url: String, prefix: String, baseHeaders: Headers): List<Video> {
        var pageUrl = url
        var voeHeaders = embedHeaders(pageUrl, "https://voe.sx", baseHeaders)
        var body = fetchText(pageUrl, voeHeaders)
        if (body.isBlank()) return emptyList()

        // Voe rota de dominio con un redirect por JS: window.location.href = "https://<mirror>/e/<id>".
        if (!body.contains("\"hls\"") && !body.contains(".m3u8")) {
            val redirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""")
                .find(body)?.groupValues?.get(1)
            if (redirect != null && redirect != pageUrl) {
                pageUrl = redirect
                voeHeaders = embedHeaders(pageUrl, "https://voe.sx", baseHeaders)
                body = fetchText(pageUrl, voeHeaders)
            }
        }

        val hls = Regex("""["']hls["']\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
            ?: Regex("""["'](?:file|source|stream|video_url|direct_access_url)["']\s*:\s*["']([^"']+m3u8[^"']*)["']""").find(body)?.groupValues?.get(1)
        val mp4 = Regex("""["']mp4["']\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
            ?: Regex("""var\s+source\s*=\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.get(1)

        val videos = mutableListOf<Video>()
        if (hls != null) videos.addAll(hlsVideos(absolutize(pageUrl, hls), prefix, baseHeaders, voeHeaders))
        if (mp4 != null) {
            val absolute = absolutize(pageUrl, mp4)
            if (!isDecoyUrl(absolute)) {
                videos.add(Video(absolute, prefix + qualityFromBody(absolute, body), absolute, voeHeaders))
            }
        }
        return distinctVideos(videos)
    }

    private fun streamWishVideos(url: String, prefix: String, baseHeaders: Headers): List<Video> {
        val hdrs = embedHeaders(url, originOf(url), baseHeaders)
        val body = fetchText(url, hdrs)
        if (body.isBlank()) return emptyList()

        val raw = Regex("""["']?(?:file|source|hls)["']?\s*[:=]\s*["']([^"']+(?:m3u8|mp4)[^"']*)["']""")
            .find(body)?.groupValues?.get(1) ?: return emptyList()
        val absolute = absolutize(url, raw)
        return if (absolute.contains(".m3u8")) {
            hlsVideos(absolute, prefix, baseHeaders, hdrs)
        } else {
            listOf(Video(absolute, prefix + qualityFromBody(absolute, body), absolute, hdrs))
        }
    }

    private fun zillaVideos(url: String, prefix: String): List<Video> {
        val hash = url.substringAfterLast("/play/").substringAfterLast("/m3u8/")
            .substringBefore("?").substringBefore("/").trim()
        if (hash.length < 16) return emptyList()
        val playlist = "https://player.zilla-networks.com/m3u8/$hash"

        // Cabeceras exactas que Cloudflare exige en Zilla: sin Origin/Referer
        // correctos devuelve 403 o corta el segmento y el player salta al final.
        val zillaHeaders = Headers.Builder()
            .set("User-Agent", userAgent)
            .set("Referer", "https://player.zilla-networks.com/play/$hash")
            .set("Origin", "https://player.zilla-networks.com")
            .set("Accept", "*/*")
            .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()

        // Warm-up: deja que cloudflareClient resuelva un posible reto y guarde la
        // cookie cf_clearance en el mismo jar que usan playlist y segmentos.
        fetchText("https://player.zilla-networks.com/play/$hash", zillaHeaders)

        return hlsVideos(playlist, prefix, zillaHeaders, zillaHeaders)
    }

    private fun genericVideos(url: String, prefix: String, baseHeaders: Headers): List<Video> {
        var pageUrl = url
        var hdrs = embedHeaders(pageUrl, originOf(pageUrl), baseHeaders)
        var body = fetchText(pageUrl, hdrs)

        val mediaRegex = Regex("""["']?(?:file|source|src|hls|playlist)["']?\s*[:=]\s*["']([^"']+(?:\.m3u8|\.mp4)[^"']*)["']""")

        // Hosts que rotan de dominio con un redirect por JS.
        if (!mediaRegex.containsMatchIn(body)) {
            val redirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""")
                .find(body)?.groupValues?.get(1)
            if (redirect != null && redirect != pageUrl) {
                pageUrl = redirect
                hdrs = embedHeaders(pageUrl, originOf(pageUrl), baseHeaders)
                body = fetchText(pageUrl, hdrs)
            }
        }
        if (body.isBlank()) return emptyList()

        val raw = mediaRegex.find(body)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""").find(body)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").find(body)?.groupValues?.get(1)
            ?: return emptyList()

        val absolute = absolutize(pageUrl, raw.replace("\\/", "/"))
        return if (absolute.contains(".m3u8")) {
            hlsVideos(absolute, prefix, baseHeaders, hdrs)
        } else {
            listOf(Video(absolute, prefix + qualityFromBody(absolute, body), absolute, hdrs))
        }
    }

    /**
     * Expande un master HLS (`#EXT-X-STREAM-INF`) en una entrada `Video` por
     * variante, con su resolución y bitrate reales. Asi el reproductor muestra
     * TODAS las calidades del m3u8 en vez de una sola entrada opaca.
     */
    private fun hlsVideos(url: String, prefix: String, baseHeaders: Headers, extra: Headers = baseHeaders): List<Video> {
        val body = fetchText(url, extra)
        if (body.isBlank() || !body.contains("#EXTM3U")) {
            return listOf(Video(url, prefix + "HLS", url, extra))
        }
        if (!body.contains("#EXT-X-STREAM-INF")) {
            return listOf(Video(url, prefix + qualityFromBody(url, body), url, extra))
        }

        val videos = mutableListOf<Video>()
        var height: String? = null
        var bandwidth: Long? = null
        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                height = normalizeQuality(Regex("""RESOLUTION=\d+x(\d{3,4})""").find(line)?.groupValues?.get(1))
                bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val currentHeight = height ?: continue
                val currentBandwidth = bandwidth
                val variant = absolutize(url, line)
                val bitrate = if (currentBandwidth != null && currentBandwidth > 0) {
                    " (" + (currentBandwidth / 1000) + " kbps)"
                } else {
                    ""
                }
                videos.add(Video(variant, prefix + currentHeight + bitrate, variant, extra))
                height = null
                bandwidth = null
            }
        }

        return videos.ifEmpty { listOf(Video(url, prefix + "HLS", url, extra)) }
    }

    private fun sortVideos(videos: List<Video>): List<Video> {
        val prefAudio = preferences.getString("preferred_audio", "all") ?: "all"
        val prefServer = preferences.getString("preferred_server", "any") ?: "any"
        val prefQuality = preferences.getString("preferred_quality", "1080p") ?: "1080p"
        return videos.sortedWith(
            compareBy<Video>(
                { audioRank(it.quality, prefAudio) },
                { serverRank(it.quality, prefServer) },
                { qualityRank(it.quality, prefQuality) },
                { -qualityHeight(it.quality) }
            )
        )
    }

    /** 0 = calidad preferida, 1 = desconocida, 2 = mayor (downscale), 3 = menor (upscale). */
    private fun qualityRank(quality: String, pref: String): Int {
        val target = pref.removeSuffix("p").toIntOrNull() ?: return 0
        val height = qualityHeight(quality)
        if (height == 0) return 1
        if (height == target) return 0
        return if (height > target) 2 else 3
    }

    private fun audioRank(quality: String, pref: String): Int = when (pref) {
        "sub" -> if (quality.contains("[SUB]")) 0 else 1
        "dub" -> if (quality.contains("[DUB]")) 0 else 1
        else -> 0
    }

    private fun serverRank(quality: String, pref: String): Int {
        val needle = when (pref) {
            "mp4upload" -> "MP4Upload"
            "voe" -> "Voe"
            "byse" -> "Byse"
            "zilla" -> "Zilla"
            "direct" -> "Direct"
            else -> return 0
        }
        return if (quality.contains(needle, ignoreCase = true)) 0 else 1
    }

    private fun qualityHeight(quality: String): Int {
        val raw = resolutionRegex.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return if (raw in 144..4320) raw else 0
    }


    // ============================== PREFERENCES ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val audioPref = ListPreference(screen.context).apply {
            key = "preferred_audio"
            title = "Audio Preferido (SUB / DUB)"
            entries = arrayOf("Mostrar Todos", "Priorizar Subtitulado", "Priorizar Doblaje (DUB)")
            entryValues = arrayOf("all", "sub", "dub")
            setDefaultValue("all")
            summary = "%s"
        }
        screen.addPreference(audioPref)

        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Servidor Preferido (Stream por Defecto)"
            entries = arrayOf("Any", "MP4Upload", "Voe", "Byse (StreamWish)", "Zilla (HLS)", "Direct")
            entryValues = arrayOf("any", "mp4upload", "voe", "byse", "zilla", "direct")
            setDefaultValue("any")
            summary = "%s"
        }
        screen.addPreference(serverPref)

        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Calidad Preferida (Stream por Defecto)"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("1080p")
            summary = "%s"
        }
        screen.addPreference(qualityPref)

        val serverBlacklistPref = MultiSelectListPreference(screen.context).apply {
            key = "server_blacklist"
            title = "Lista Negra de Servidores (Ocultar)"
            entries = arrayOf("MP4Upload", "Voe", "Byse (StreamWish)", "Zilla (HLS)", "Directos")
            entryValues = arrayOf("mp4upload", "voe", "byse", "zilla", "direct")
            setDefaultValue(emptySet<String>())
            summary = "Desactiva servidores en el reproductor (ej. Zilla)"
        }
        screen.addPreference(serverBlacklistPref)

        val genreBlacklistPref = EditTextPreference(screen.context).apply {
            key = "genre_blacklist_text"
            title = "Lista Negra de Géneros (# - Ocultar)"
            setDefaultValue("")
            summary = "Oculta permanentemente animes con estos géneros (#) en el catálogo"
            dialogTitle = "Lista Negra de Géneros (# - Ocultar)"
        }
        screen.addPreference(genreBlacklistPref)
    }
}
