package top.supergeoff.dictee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TranscriptionClient {
    suspend fun submit(task: TranscriptionTask): SubmitResult
    suspend fun get(task: TranscriptionTask): ServerTask
    suspend fun cancel(task: TranscriptionTask): ServerTask
    suspend fun test(settings: TranscriptionSettings)
}

sealed interface SubmitResult {
    data class Accepted(val task: ServerTask) : SubmitResult
    /** À sauvegarder durablement avant SAVING : aucun ID ne permet de récupérer cette réponse. */
    data class DirectVtt(val bytes: ByteArray) : SubmitResult
    /** Réservé à un signal de refus du champ background documenté et fiable. */
    data object BackgroundUnsupported : SubmitResult
}

enum class ServerStatus(val wire: String) {
    QUEUED("queued"), IN_PROGRESS("in_progress"), COMPLETED("completed"), FAILED("failed"), CANCELLED("cancelled")
}

/** output conserve le contenu VTT en UTF-8, sans normalisation. */
data class ServerTask(val id: String, val status: ServerStatus, val progress: Double?, val output: ByteArray?)

open class TranscriptionClientException(message: String) : Exception(message)
class TranscriptionNetworkException : TranscriptionClientException("La connexion au service de transcription a échoué ou a expiré.")
class TranscriptionHttpException(val code: Int) : TranscriptionClientException("Le service de transcription a répondu avec une erreur HTTP $code.")
class TranscriptionProtocolException : TranscriptionClientException("La réponse du service de transcription est invalide ou incomplète.")

/** Transport injectable. Le temps total inclut la lecture de la source et l'écriture multipart. */
class UrlTranscriptionClient(
    private val credentials: CredentialStore,
    private val openMp3: (TranscriptionTask) -> InputStream,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val totalTimeoutMs: Long = 7_200_000,
    private val log: Log? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : TranscriptionClient {
    init { require(totalTimeoutMs > 0) }

    /** Dernier pourcentage entier journalisé par identifiant serveur, pour n'écrire qu'au changement. */
    private val lastLoggedPercent = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private enum class FailureKind { CREDENTIAL, PROTOCOL, HTTP }
    private class InternalFailure(val kind: FailureKind, val code: Int = 0) : Exception()

    private fun safeFailure(failure: InternalFailure): Exception = when (failure.kind) {
        FailureKind.CREDENTIAL -> CredentialException()
        FailureKind.PROTOCOL -> TranscriptionProtocolException()
        FailureKind.HTTP -> TranscriptionHttpException(failure.code)
    }

    override suspend fun submit(task: TranscriptionTask): SubmitResult = execute { resources ->
        val started = nanoTime()
        val connection = connection(task.serverAddress, task.encryptedKeyRef, "/audio/transcriptions", "POST", resources)
        val boundary = "dictee-" + UUID.randomUUID()
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setChunkedStreamingMode(64 * 1024)
        connection.outputStream.use { output ->
            fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", task.model)
            field("response_format", "vtt")
            field("background", "true")
            field("language", task.language)
            val filename = task.mp3Name.replace(Regex("[\\\"\r\n]"), "_")
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: audio/mpeg\r\n\r\n")
            openMp3(task).use { source -> resources.registerSource(source); source.copyTo(output, 64 * 1024) }
            resources.source.set(null)
            write("\r\n--$boundary--\r\n")
        }
        val bytes = response(connection)
        log?.add(Source.STT, "${nameOf(task)} : MP3 envoyé, réponse ${connection.responseCode} en ${(nanoTime() - started) / 1_000_000_000} s")
        if (isVtt(bytes)) SubmitResult.DirectVtt(bytes) else SubmitResult.Accepted(parseTask(bytes))
    }

    override suspend fun get(task: TranscriptionTask): ServerTask = execute { resources ->
        val id = safeId(task.serverId)
        val c = connection(task.serverAddress, task.encryptedKeyRef, "/audio/transcriptions/$id?response_format=vtt", "GET", resources)
        parseTask(response(c)).also { server ->
            if (server.id != id) throw InternalFailure(FailureKind.PROTOCOL)
            val percent = server.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
            if (percent != null && lastLoggedPercent.put(id, percent) != percent)
                log?.add(Source.STT, "${nameOf(task)} : serveur $percent %")
        }
    }

    override suspend fun cancel(task: TranscriptionTask): ServerTask = execute { resources ->
        val id = safeId(task.serverId)
        val c = connection(task.serverAddress, task.encryptedKeyRef, "/audio/transcriptions/$id/cancel", "POST", resources)
        parseTask(response(c)).also { if (it.id != id) throw InternalFailure(FailureKind.PROTOCOL) }
    }

    override suspend fun test(settings: TranscriptionSettings) {
        execute { resources ->
            val c = connection(settings.baseUrl, settings.credentialRef ?: "", "/models", "GET", resources)
            response(c)
        }
    }

    private fun safeId(id: String?): String {
        if (id.isNullOrEmpty() || !id.matches(Regex("[A-Za-z0-9_-]+"))) throw InternalFailure(FailureKind.PROTOCOL)
        return id
    }

    private fun connection(base: String, ref: String, path: String, method: String, resources: Resources): HttpURLConnection {
        if (base == "/v1" || !isValidTranscriptionBaseUrl(base)) throw InternalFailure(FailureKind.PROTOCOL)
        if (ref.isBlank()) throw InternalFailure(FailureKind.CREDENTIAL)
        val key = try { credentials.read(ref) } catch (_: Exception) { throw InternalFailure(FailureKind.CREDENTIAL) }
        if (key.isBlank() || key.any { it == '\r' || it == '\n' }) throw InternalFailure(FailureKind.CREDENTIAL)
        val c = openConnection(URL(base + path))
        resources.connection.set(c)
        if (resources.cancelled.get()) { c.disconnect(); throw CancellationException() }
        c.instanceFollowRedirects = false
        c.connectTimeout = minOf(30_000L, totalTimeoutMs).toInt().coerceAtLeast(1)
        c.readTimeout = minOf(Int.MAX_VALUE.toLong(), totalTimeoutMs).toInt().coerceAtLeast(1)
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $key")
        return c
    }

    private fun response(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) throw InternalFailure(FailureKind.HTTP, code)
        val bytes = c.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
        val length = c.contentLengthLong
        if (length >= 0 && length != bytes.size.toLong()) throw InternalFailure(FailureKind.PROTOCOL)
        return bytes
    }

    private fun isVtt(bytes: ByteArray): Boolean {
        val text = decode(bytes)
        val signature = if (text.startsWith('\uFEFF')) text.substring(1) else text
        return signature == "WEBVTT" || (signature.startsWith("WEBVTT") &&
            signature.length > 6 && signature[6] in charArrayOf('\n', '\r', ' ', '\t'))
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw InternalFailure(FailureKind.PROTOCOL) }

    private fun parseTask(bytes: ByteArray): ServerTask = try {
        val json = JSONObject(decode(bytes))
        val id = safeId(json.opt("id") as? String)
        val status = ServerStatus.entries.firstOrNull { it.wire == json.opt("status") } ?: throw InternalFailure(FailureKind.PROTOCOL)
        val raw = json.opt("progress")
        val progress = if (raw == null || raw == JSONObject.NULL) null else {
            val value = (raw as? Number)?.toDouble() ?: throw InternalFailure(FailureKind.PROTOCOL)
            if (!value.isFinite() || value !in 0.0..1.0) throw InternalFailure(FailureKind.PROTOCOL)
            value
        }
        val output = when (val rawOutput = json.opt("output")) {
            null, JSONObject.NULL -> null
            is String -> rawOutput.toByteArray(Charsets.UTF_8).also { if (!isVtt(it)) throw InternalFailure(FailureKind.PROTOCOL) }
            else -> throw InternalFailure(FailureKind.PROTOCOL)
        }
        if (status == ServerStatus.COMPLETED && output == null) throw InternalFailure(FailureKind.PROTOCOL)
        ServerTask(id, status, progress, output)
    } catch (e: InternalFailure) { throw e }
      catch (_: Exception) { throw InternalFailure(FailureKind.PROTOCOL) }

    private class Resources {
        val connection = AtomicReference<HttpURLConnection?>()
        val source = AtomicReference<InputStream?>()
        val cancelled = AtomicBoolean(false)
        fun registerSource(input: InputStream) {
            source.set(input)
            if (cancelled.get()) throw CancellationException()
        }
        fun abort() {
            cancelled.set(true)
            // Ne bloque pas la coroutine si le fournisseur ou le transport bloque sur close.
            executor.execute { try { connection.get()?.disconnect() } catch (_: Exception) {} }
            executor.execute { try { source.get()?.close() } catch (_: Exception) {} }
        }
    }

    private suspend fun <T : Any> execute(block: (Resources) -> T): T {
        val result = withTimeoutOrNull(totalTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val resources = Resources()
                val future = AtomicReference<Future<*>?>()
                continuation.invokeOnCancellation { resources.abort(); future.get()?.cancel(true) }
                future.set(executor.submit {
                    try {
                        val value = block(resources)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(TranscriptionNetworkException())
                    } catch (e: InternalFailure) {
                        // Un fournisseur peut ajouter des exceptions supprimées, même à une erreur interne.
                        if (continuation.isActive) continuation.resumeWithException(safeFailure(e))
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(TranscriptionNetworkException())
                    } finally {
                        try { resources.connection.get()?.disconnect() } catch (_: Exception) {}
                    }
                })
                if (!continuation.isActive) future.get()?.cancel(true)
            }
        }
        return result ?: throw TranscriptionNetworkException()
    }

    companion object {
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "transcription-http").apply { isDaemon = true } }
        private fun nameOf(task: TranscriptionTask): String = task.mp3Name.removeSuffix(".mp3")
    }
}
