package eu.kanade.tachiyomi.animeextension.es.animeav1.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class StreamWishExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, videoNameHost: String = "Byse - "): List<Video> {
        val request = Request.Builder().url(url).build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return emptyList()
        val body = response.body.string()

        val m3u8Match = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(body)
            ?: Regex("""source:\s*["']([^"']+\.m3u8[^"']*)["']""").find(body)

        val streamUrl = m3u8Match?.groupValues?.get(1) ?: return emptyList()
        val headers = Headers.Builder().set("Referer", url).build()

        return listOf(
            Video(videoUrl = streamUrl, videoTitle = "${videoNameHost}HLS", headers = headers)
        )
    }
}
