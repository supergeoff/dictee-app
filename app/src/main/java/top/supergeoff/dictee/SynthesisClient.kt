package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.Reader
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
                c.errorStream?.use { input ->
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    ErrorCodeReader(InputStreamReader(input, decoder).buffered()).isContextTooLong()
                } ?: false
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

/** Lit l'erreur JSON sans conserver les chaînes fournisseur, potentiellement très longues. */
private class ErrorCodeReader(private val input: Reader) {
    private var next = input.read()

    fun isContextTooLong(): Boolean {
        whitespace()
        val result = objectValue(0, root = true)
        whitespace()
        require(next == -1)
        return result
    }

    private fun advance(): Int = next.also { next = input.read() }
    private fun whitespace() { while (next == 32 || next == 9 || next == 10 || next == 13) advance() }
    private fun take(char: Char) { require(next == char.code); advance(); whitespace() }

    private fun objectValue(depth: Int, root: Boolean = false, error: Boolean = false): Boolean {
        require(depth < 64)
        take('{')
        var result = false
        if (next != '}'.code) while (true) {
            val key = string(if (root) "error" else if (error) "code" else "")
            whitespace()
            take(':')
            if (root && key) {
                result = if (next == '{'.code) objectValue(depth + 1, error = true) else { value(depth + 1); false }
            } else if (error && key) {
                result = if (next == '"'.code) string("context_length_exceeded") else { value(depth + 1); false }
            } else value(depth + 1)
            whitespace()
            if (next != ','.code) break
            take(',')
        }
        take('}')
        return result
    }

    private fun value(depth: Int) {
        require(depth < 64)
        when (next) {
            '{'.code -> objectValue(depth)
            '['.code -> {
                take('[')
                if (next != ']'.code) while (true) {
                    value(depth + 1)
                    whitespace()
                    if (next != ','.code) break
                    take(',')
                }
                take(']')
            }
            '"'.code -> string("")
            't'.code -> literal("true")
            'f'.code -> literal("false")
            'n'.code -> literal("null")
            else -> number()
        }
    }

    private fun literal(expected: String) { for (char in expected) { require(next == char.code); advance() } }

    private fun number() {
        if (next == '-'.code) advance()
        if (next == '0'.code) advance() else {
            require(next in '1'.code..'9'.code)
            while (next in '0'.code..'9'.code) advance()
        }
        if (next == '.'.code) { advance(); digits() }
        if (next == 'e'.code || next == 'E'.code) {
            advance()
            if (next == '+'.code || next == '-'.code) advance()
            digits()
        }
    }

    private fun digits() {
        require(next in '0'.code..'9'.code)
        while (next in '0'.code..'9'.code) advance()
    }

    private fun string(expected: String): Boolean {
        require(advance() == '"'.code)
        var index = 0
        var matches = true
        while (next != '"'.code) {
            require(next >= 32)
            val char = if (next == '\\'.code) {
                advance()
                when (val escape = advance()) {
                    '"'.code, '\\'.code, '/'.code -> escape
                    'b'.code -> 8
                    'f'.code -> 12
                    'n'.code -> 10
                    'r'.code -> 13
                    't'.code -> 9
                    'u'.code -> {
                        var code = 0
                        repeat(4) {
                            val digit = advance()
                            code = code * 16 + when (digit) {
                                in '0'.code..'9'.code -> digit - '0'.code
                                in 'a'.code..'f'.code -> digit - 'a'.code + 10
                                in 'A'.code..'F'.code -> digit - 'A'.code + 10
                                else -> throw IllegalArgumentException()
                            }
                        }
                        code
                    }
                    else -> throw IllegalArgumentException()
                }
            } else advance()
            if (index >= expected.length || expected[index].code != char) matches = false
            if (index < expected.length) index++
        }
        advance()
        return matches && index == expected.length
    }
}
