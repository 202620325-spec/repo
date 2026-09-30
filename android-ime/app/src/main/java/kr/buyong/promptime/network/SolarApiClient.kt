package kr.buyong.promptime.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SolarApiException(val statusCode: Int, message: String) : Exception(message)

data class SolarCompletion(
    val text: String,
    val networkMs: Long,
    val firstTokenMs: Long
)

class SolarApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun streamCompletion(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        onPartial: suspend (String, Long) -> Unit
    ): SolarCompletion = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val json = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.18)
            .put("top_p", 0.92)
            .put("reasoning_effort", "minimal")

        val request = Request.Builder()
            .url("https://api.upstage.ai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val started = System.nanoTime()
        val call = http.newCall(request)
        val handle = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
            if (it is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty().take(1000)
                    throw SolarApiException(response.code, body.ifBlank { "HTTP ${response.code}" })
                }
                val source = response.body?.source() ?: throw SolarApiException(response.code, "Empty response body")
                val out = StringBuilder()
                var firstTokenMs = -1L
                while (currentCoroutineContext().isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isBlank()) continue
                    val obj = JSONObject(payload)
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val piece = delta.optString("content", "")
                    if (piece.isEmpty()) continue
                    out.append(piece)
                    if (firstTokenMs < 0) firstTokenMs = elapsedMs(started)
                    onPartial(out.toString(), firstTokenMs)
                }
                SolarCompletion(out.toString(), elapsedMs(started), firstTokenMs.coerceAtLeast(0L))
            }
        } finally {
            handle?.dispose()
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L
}
