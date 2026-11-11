package eu.kanade.tachiyomi.animeextension.es.animeav1.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

class ZillaExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = "[SUB] Zilla - "): List<Video> {
        val hash = url.substringAfterLast("/play/").substringBefore("?").trim()
        if (hash.isEmpty() || hash.length < 16) return emptyList()

        val playlistUrl = "https://player.zilla-networks.com/m3u8/$hash"

        // Cabeceras exactas que Cloudflare exige en Zilla para evitar el 403 Forbidden
        val videoHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .set("Referer", "https://player.zilla-networks.com/play/$hash")
            .set("Origin", "https://player.zilla-networks.com")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Dest", "empty")
            .set("Accept", "*/*")
            .build()

        return runCatching {
            listOf(
                Video(
                    url = playlistUrl,
                    quality = "${prefix}HLS (Original)",
                    videoUrl = playlistUrl,
                    headers = videoHeaders
                )
            )
        }.getOrDefault(emptyList())
    }
}
