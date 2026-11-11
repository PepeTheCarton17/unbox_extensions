package eu.kanade.tachiyomi.animeextension.es.animeav1.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class Mp4uploadExtractor(private val client: OkHttpClient) {

    fun getVideoFromUrl(url: String, headers: Headers, prefix: String = "[SUB] MP4Upload - "): List<Video> {
        val request = Request.Builder().url(url).headers(headers).build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return emptyList()
        val body = response.body.string()

        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .build()

        val videoList = mutableListOf<Video>()

        // 1. Múltiples calidades con label
        val multiMatches = Regex("""\{\s*src:\s*["']([^"']+)["'],\s*type:\s*["'][^"']+["'](?:,\s*label:\s*["']([^"']+)["'])?""").findAll(body)
        for (m in multiMatches) {
            val src = m.groupValues[1]
            val label = m.groupValues[2].ifEmpty { "720p" }
            videoList.add(Video(src, "${prefix}$label", src, headers = videoHeaders))
        }

        if (videoList.isNotEmpty()) {
            return videoList
        }

        // 2. Extracción directa clásica
        val srcMatch = Regex("""player\.src\(\s*["']([^"']+)["']""").find(body)
            ?: Regex("""src:\s*["']([^"']+\.mp4[^"']*)["']""").find(body)
            ?: Regex("""["']file["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(body)

        val streamUrl = srcMatch?.groupValues?.get(1) ?: return emptyList()
        val quality = if (body.contains("1080p")) "1080p" else if (body.contains("720p")) "720p" else "480p"

        return listOf(
            Video(streamUrl, "${prefix}$quality", streamUrl, headers = videoHeaders)
        )
    }
}
