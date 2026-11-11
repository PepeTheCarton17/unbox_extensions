package eu.kanade.tachiyomi.animeextension.es.animeav1.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class VoeExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, prefix: String = "Voe - "): List<Video> {
        val request = Request.Builder().url(url).build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return emptyList()
        val body = response.body.string()

        val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(body)
            ?: Regex("""['"]file['"]\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(body)
        val mp4Match = Regex("""['"]mp4['"]\s*:\s*['"]([^'"]+)['"]""").find(body)

        val videoList = mutableListOf<Video>()
        hlsMatch?.groupValues?.get(1)?.let { streamUrl ->
            videoList.add(Video(videoUrl = streamUrl, videoTitle = "${prefix}HLS (Original)"))
        }
        mp4Match?.groupValues?.get(1)?.let { streamUrl ->
            videoList.add(Video(videoUrl = streamUrl, videoTitle = "${prefix}MP4 (Directo)"))
        }

        return videoList
    }
}
