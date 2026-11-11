package eu.kanade.tachiyomi.animeextension.es.animeav1.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class Mp4uploadExtractor(private val client: OkHttpClient) {

    fun getVideoFromUrl(url: String, headers: Headers, prefix: String = "MP4Upload - "): List<Video> {
        val request = Request.Builder().url(url).headers(headers).build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return emptyList()
        val body = response.body.string()

        val srcMatch = Regex("""player\.src\(\s*["']([^"']+)["']""").find(body)
            ?: Regex("""src:\s*["']([^"']+\.mp4[^"']*)["']""").find(body)
            ?: Regex("""["']file["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(body)

        val streamUrl = srcMatch?.groupValues?.get(1) ?: return emptyList()
        val videoHeaders = headers.newBuilder()
            .set("Referer", url)
            .build()

        return listOf(
            Video(videoUrl = streamUrl, videoTitle = "${prefix}MP4 (Directo)", headers = videoHeaders)
        )
    }
}
