package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface SynthesisClient {
    suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray
    suspend fun test(settings: SynthesisSettings, secret: String)
}

enum class SynthesisFailureKind { TRANSIENT, PERMANENT, INVALID_RESPONSE, CREDENTIAL }

class SynthesisClientException(val kind: SynthesisFailureKind) : Exception(when (kind) {
    SynthesisFailureKind.TRANSIENT -> "La connexion au service de synthèse a échoué ou a expiré."
    SynthesisFailureKind.PERMANENT -> "La requête de synthèse a été refusée ou sa configuration est invalide."
    SynthesisFailureKind.INVALID_RESPONSE -> "La réponse du service de synthèse est invalide ou incomplète."
    SynthesisFailureKind.CREDENTIAL -> "Clé de synthèse inaccessible ou invalide."
})

class UrlSynthesisClient(
    private val credentials: CredentialStore,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val timeoutMs: Long = 7_200_000,
) : SynthesisClient {
    init { require(timeoutMs > 0) }

    private class Failure(val kind: SynthesisFailureKind) : Exception()

    override suspend fun complete(context: SynthesisContext, vtt: ByteArray): ByteArray = execute { resources ->
        val prompt = context.systemPrompt
        if (prompt.isNullOrBlank() || context.vttSha256.isNullOrBlank()) throw Failure(SynthesisFailureKind.PERMANENT)
        val content = decode(vtt, SynthesisFailureKind.PERMANENT)
        request(context.baseUrl, context.model, prompt, content, resources) {
            if (context.credentialRef.isBlank()) throw Failure(SynthesisFailureKind.CREDENTIAL)
            try { credentials.read(context.credentialRef) } catch (_: Exception) { throw Failure(SynthesisFailureKind.CREDENTIAL) }
        }
    }

    override suspend fun test(settings: SynthesisSettings, secret: String) {
        execute { resources ->
            request(settings.baseUrl, settings.model, settings.prompt, "Réponds brièvement pour confirmer la connexion.", resources) { secret }
        }
    }

    private fun request(base: String, model: String, prompt: String, user: String, resources: Resources, secret: () -> String): ByteArray {
        if (base == "/v1" || !isValidTranscriptionBaseUrl(base) || model.isBlank() || prompt.isBlank()) throw Failure(SynthesisFailureKind.PERMANENT)
        val key = secret()
        if (key.isBlank() || key.any { it == '\r' || it == '\n' }) throw Failure(SynthesisFailureKind.CREDENTIAL)
        val c = openConnection(URL(base + "/chat/completions"))
        resources.connection.set(c)
        if (resources.cancelled.get()) { c.disconnect(); throw CancellationException() }
        c.instanceFollowRedirects = false
        c.connectTimeout = minOf(30_000L, timeoutMs).toInt().coerceAtLeast(1)
        c.readTimeout = minOf(Int.MAX_VALUE.toLong(), timeoutMs).toInt().coerceAtLeast(1)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt))
            .put(JSONObject().put("role", "user").put("content", user))
        val bytes = JSONObject().put("model", model).put("messages", messages).toString().toByteArray(Charsets.UTF_8)
        c.outputStream.use { it.write(bytes) }
        return parseResponse(response(c))
    }

    private fun response(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) {
            val contextTooLong = if (code == 429 || code in 500..599) try {
                val bytes = c.errorStream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (out.size() < 16 * 1024) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 16 * 1024 - out.size()))
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                JSONObject(decode(bytes, SynthesisFailureKind.INVALID_RESPONSE))
                    .optJSONObject("error")?.opt("code") == "context_length_exceeded"
            } catch (_: Exception) { false } else false
            val kind = if (contextTooLong) SynthesisFailureKind.PERMANENT else when (code) {
                429, in 500..599 -> SynthesisFailureKind.TRANSIENT
                else -> SynthesisFailureKind.PERMANENT
            }
            throw Failure(kind)
        }
        val bytes = c.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
        if (c.contentLengthLong >= 0 && c.contentLengthLong != bytes.size.toLong()) throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        return bytes
    }

    private fun decode(bytes: ByteArray, failure: SynthesisFailureKind): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw Failure(failure) }

    private fun parseResponse(bytes: ByteArray): ByteArray = try {
        val choice = JSONObject(decode(bytes, SynthesisFailureKind.INVALID_RESPONSE)).optJSONArray("choices")?.optJSONObject(0)
            ?: throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        val finish = choice.opt("finish_reason")
        if (finish != null && finish != JSONObject.NULL && finish != "stop") throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        val message = choice.optJSONObject("message") ?: throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        if (message.opt("role") != "assistant") throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        val refusal = message.opt("refusal")
        if (refusal != null && refusal != JSONObject.NULL) throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        val toolCalls = message.opt("tool_calls")
        if (toolCalls != null && toolCalls != JSONObject.NULL) throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        val text = message.opt("content") as? String ?: throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        if (text.isBlank()) throw Failure(SynthesisFailureKind.INVALID_RESPONSE)
        text.toByteArray(Charsets.UTF_8)
    } catch (e: Failure) { throw e }
      catch (_: Exception) { throw Failure(SynthesisFailureKind.INVALID_RESPONSE) }

    private class Resources {
        val connection = AtomicReference<HttpURLConnection?>()
        val cancelled = AtomicBoolean(false)
        fun abort() {
            cancelled.set(true)
            executor.execute { try { connection.get()?.disconnect() } catch (_: Exception) {} }
        }
    }

    private suspend fun <T : Any> execute(block: (Resources) -> T): T {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val resources = Resources()
                val future = AtomicReference<Future<*>?>()
                continuation.invokeOnCancellation { resources.abort(); future.get()?.cancel(true) }
                future.set(executor.submit {
                    try {
                        val value = block(resources)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Failure) {
                        if (continuation.isActive) continuation.resumeWithException(SynthesisClientException(e.kind))
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(SynthesisClientException(SynthesisFailureKind.TRANSIENT))
                    } finally {
                        try { resources.connection.get()?.disconnect() } catch (_: Exception) {}
                    }
                })
                if (!continuation.isActive) future.get()?.cancel(true)
            }
        }
        return result ?: throw SynthesisClientException(SynthesisFailureKind.TRANSIENT)
    }

    companion object {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "synthesis-http").apply { isDaemon = true } }
    }
}
