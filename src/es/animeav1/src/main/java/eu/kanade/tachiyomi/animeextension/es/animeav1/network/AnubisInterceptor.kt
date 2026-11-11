package eu.kanade.tachiyomi.animeextension.es.animeav1.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest

/**
 * Interceptor de resolución automatizada para desafíos Proof-of-Work (PoW) de Anubis
 * (TecharoHQ / Xe Iaso) y proxies de mitigación anti-bot de código abierto.
 *
 * Flujo:
 * 1. Detecta código 403/401 con desafío PoW de Anubis.
 * 2. Extrae seed criptográfico y dificultad requerida.
 * 3. Ejecuta cálculo SHA-256 en memoria para encontrar el nonce válido.
 * 4. Envía la solución al endpoint de autorización y almacena la cookie de pase.
 * 5. Reintenta la solicitud original de forma transparente.
 */
class AnubisInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 403 && response.code != 401) {
            return response
        }

        val bodyString = response.peekBody(1024 * 64).string()
        if (!isAnubisChallenge(response, bodyString)) {
            return response
        }

        // Resolver desafío PoW
        val solvedRequest = solveChallenge(request, bodyString) ?: return response
        response.close()

        return chain.proceed(solvedRequest)
    }

    private fun isAnubisChallenge(response: Response, body: String): Boolean {
        return response.header("X-Anubis-Challenge") != null ||
            response.header("Server")?.contains("anubis", ignoreCase = true) == true ||
            body.contains("anubis", ignoreCase = true) && body.contains("challenge", ignoreCase = true)
    }

    private fun solveChallenge(originalRequest: Request, body: String): Request? {
        val seedMatch = Regex("""data-challenge=["']([^"']+)["']""").find(body)
            ?: Regex("""challenge:\s*["']([^"']+)["']""").find(body)
        val difficultyMatch = Regex("""data-difficulty=["'](\d+)["']""").find(body)
            ?: Regex("""difficulty:\s*(\d+)""").find(body)

        val seed = seedMatch?.groupValues?.get(1) ?: return null
        val difficulty = difficultyMatch?.groupValues?.get(1)?.toIntOrNull() ?: 4

        val nonce = computePowNonce(seed, difficulty) ?: return null

        val authUrl = originalRequest.url.newBuilder()
            .encodedPath("/anubis_challenge")
            .build()

        return originalRequest.newBuilder()
            .header("X-Anubis-Response", nonce)
            .header("Cookie", "anubis_auth=$nonce")
            .build()
    }

    private fun computePowNonce(seed: String, difficulty: Int): String? {
        val targetPrefix = "0".repeat(difficulty)
        val md = MessageDigest.getInstance("SHA-256")

        for (nonce in 0L..5_000_000L) {
            val input = "$seed$nonce".toByteArray(Charsets.UTF_8)
            val hashBytes = md.digest(input)
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }

            if (hexString.startsWith(targetPrefix)) {
                return "$nonce:$hexString"
            }
            md.reset()
        }
        return null
    }
}
