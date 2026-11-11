package top.supergeoff.dictee

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class FakeCredentials : CredentialStore {
    val refs = mutableListOf<String>()
    override fun read(ref: String): String { refs += ref; return "synthetic-secret" }
    override fun save(secret: String) = "ref"
    override fun remove(ref: String) {}
}
private class FakeConnection(url: URL, val status: Int = 200, val body: ByteArray = ByteArray(0), val type: String = "application/json") : HttpURLConnection(url) {
    val written = ByteArrayOutputStream()
    @Volatile var closed = false
    var outputFactory: (() -> OutputStream)? = null
    override fun connect() {}
    override fun disconnect() { closed = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentType() = type
    override fun getContentLengthLong() = body.size.toLong()
    override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    override fun getOutputStream(): OutputStream = outputFactory?.invoke() ?: written
}
class TranscriptionClientTest {
    // La récupération de pile des coroutines peut copier une exception sûre et chaîner l'originale.
    private fun assertSafeError(e: Throwable) {
        val stack = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(stack.contains("synthetic-secret"))
        var cause: Throwable? = e
        while (cause != null) {
            assertTrue(cause is TranscriptionClientException || cause is CredentialException)
            cause = cause.cause
        }
    }
    private val creds = FakeCredentials()
    private val connections = mutableListOf<FakeConnection>()
    private val responses = ArrayDeque<Triple<Int, String, String>>()
    private val mp3 = byteArrayOf(1, 2, 0, -1, 42)
    private fun reply(status: Int, body: String, type: String = "application/json") { responses.add(Triple(status, body, type)) }
    private fun client(timeout: Long = 3000) = UrlTranscriptionClient(creds, { ByteArrayInputStream(mp3) }, { url ->
        val (code, body, type) = responses.removeFirst()
        FakeConnection(url, code, body.toByteArray(), type).also { connections += it }
    }, timeout)
    private fun task(id: String? = null) = TranscriptionTask("local", "synthetic", "audio.mp3", "audio.vtt", "folder", "hash", "https://example.invalid/v1", "old-ref", "fr", "dictee-diarize", TranscriptionPhase.PENDING, id)
    private fun json(status: String, extra: String = "") = "{\"id\":\"job-1\",\"status\":\"$status\"$extra}"
    @Test fun multipartAndFrozenContext() = runBlocking {
        reply(200, json("queued", ",\"progress\":0.25"))
        val result = client().submit(task()) as SubmitResult.Accepted
        assertEquals("job-1", result.task.id); assertEquals(0.25, result.task.progress!!, 0.0)
        val c = connections.single(); assertEquals("https://example.invalid/v1/audio/transcriptions", c.url.toString())
        assertEquals("POST", c.requestMethod); assertEquals("Bearer synthetic-secret", c.getRequestProperty("Authorization"))
        val body = c.written.toByteArray().toString(Charsets.ISO_8859_1)
        listOf("name=\"file\"; filename=\"audio.mp3\"", "Content-Type: audio/mpeg", "name=\"model\"\r\n\r\ndictee-diarize", "name=\"response_format\"\r\n\r\nvtt", "name=\"background\"\r\n\r\ntrue", "name=\"language\"\r\n\r\nfr", mp3.toString(Charsets.ISO_8859_1)).forEach { assertTrue(it, body.contains(it)) }
        assertEquals(listOf("old-ref"), creds.refs); assertTrue(c.closed)
    }
    @Test fun routesStatusesAndOutput() = runBlocking {
        val c = client()
        for (status in listOf("queued", "in_progress", "completed", "failed", "cancelled")) {
            val extra = if (status == "completed") ",\"output\":\"WEBVTT\\n\\n\"" else ",\"progress\":null"
            reply(200, json(status, extra)); val value = c.get(task("job-1"))
            assertEquals(status, value.status.wire); assertNull(value.progress)
            if (status == "completed") assertArrayEquals("WEBVTT\n\n".toByteArray(), value.output)
        }
        reply(200, json("cancelled")); c.cancel(task("job-1"))
        reply(200, "{}"); c.test(TranscriptionSettings("https://example.invalid/v1", credentialRef = "test-ref"))
        assertTrue(connections[0].url.toString().endsWith("/audio/transcriptions/job-1?response_format=vtt"))
        assertEquals("POST", connections[5].requestMethod); assertTrue(connections[5].url.path.endsWith("/job-1/cancel"))
        assertEquals("GET", connections[6].requestMethod); assertTrue(connections[6].url.path.endsWith("/v1/models"))
    }
    @Test fun directVttKeepsEveryByte() = runBlocking {
        val bytes = "WEBVTT\r\n\r\n00:00:00.000 --> 00:00:01.000\r\n<v A>é\r\n".toByteArray()
        reply(200, bytes.toString(Charsets.UTF_8), "text/vtt")
        assertArrayEquals(bytes, (client().submit(task()) as SubmitResult.DirectVtt).bytes)
        reply(200, "WEBVTT\n\n", "text/vtt"); assertArrayEquals("WEBVTT\n\n".toByteArray(), (client().submit(task()) as SubmitResult.DirectVtt).bytes)
    }
    @Test fun errorsNeverRetryOrLeak() = runBlocking {
        for ((code, body) in listOf(400 to "{\"error\":\"synthetic-secret\"}", 401 to "synthetic-secret", 404 to "synthetic-secret", 429 to "{\"error\":{\"code\":\"long_audio_busy\"}}", 200 to "{\"id\":\"job-1\"}")) {
            val before = connections.size; reply(code, body)
            try { client().submit(task()); fail("expected failure") } catch (e: TranscriptionClientException) {
                assertSafeError(e)
                if (code != 200) assertTrue(e is TranscriptionHttpException && e.code == code) else assertTrue(e is TranscriptionProtocolException)
            }
            assertEquals(before + 1, connections.size)
        }
    }
    @Test fun invalidProgressAndIncompleteJsonAreRejected() = runBlocking {
        val c = client()
        for (extra in listOf(",\"progress\":-0.1", ",\"progress\":1.01", ",\"progress\":\"0.2\"", ",\"progress\":1e999")) {
            reply(200, json("in_progress", extra))
            try { c.get(task("job-1")); fail() } catch (_: TranscriptionProtocolException) {}
        }
        reply(200, json("completed")); try { c.get(task("job-1")); fail() } catch (_: TranscriptionProtocolException) {}
    }
    @Test fun interruptedUploadDoesNotRetry() = runBlocking {
        val calls = mutableListOf<FakeConnection>()
        val c = UrlTranscriptionClient(creds, { ByteArrayInputStream(mp3) }, { url -> FakeConnection(url).also { fake ->
            fake.outputFactory = { object : OutputStream() { override fun write(b: Int) { throw IOException("synthetic-secret") } } }; calls += fake
        } }, 1000)
        try { c.submit(task()); fail() } catch (e: TranscriptionNetworkException) { assertSafeError(e) }
        assertEquals(1, calls.size); assertTrue(calls.single().closed)
    }
    @Test fun timeoutClosesConnectionDuringUpload() = runBlocking {
        val entered = CountDownLatch(1); lateinit var fake: FakeConnection; var attempts = 0
        val c = UrlTranscriptionClient(creds, { ByteArrayInputStream(mp3) }, { url -> FakeConnection(url).also { f ->
            attempts++; fake = f; f.outputFactory = { object : OutputStream() { override fun write(b: Int) { entered.countDown(); while (!f.closed) Thread.sleep(5) } } }
        } }, 100)
        try { c.submit(task()); fail() } catch (_: TranscriptionNetworkException) {}
        assertTrue(entered.await(1, TimeUnit.SECONDS)); withTimeout(1000) { while (!fake.closed) delay(5) }; assertEquals(1, attempts)
    }
    @Test fun cancellationClosesConnectionWithoutRetry() = runBlocking {
        val entered = CountDownLatch(1)
        val calls = mutableListOf<FakeConnection>()
        val c = UrlTranscriptionClient(creds, { ByteArrayInputStream(mp3) }, { url ->
            FakeConnection(url).also { f ->
                f.outputFactory = { object : OutputStream() {
                    override fun write(b: Int) { entered.countDown(); while (!f.closed) Thread.sleep(5) }
                } }
                calls += f
            }
        }, 3000)
        val job = launch { c.submit(task()) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(1, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        withTimeout(1000) { while (!calls.single().closed) delay(5) }
        assertEquals(1, calls.size)
    }
    @Test fun credentialFailureIsSafeAndPreventsNetwork() = runBlocking {
        val broken = object : CredentialStore {
            override fun read(ref: String): String = throw IOException("synthetic-secret")
            override fun save(secret: String) = "ref"
            override fun remove(ref: String) {}
        }
        var calls = 0
        val c = UrlTranscriptionClient(broken, { ByteArrayInputStream(mp3) }, { calls++; error("unexpected network") })
        try { c.submit(task()); fail() } catch (e: CredentialException) { assertSafeError(e) }
        assertEquals(0, calls)
    }

    @Test fun progressBoundariesAndJsonOutputPreserveContent() = runBlocking {
        val c = client()
        for (n in listOf(0, 1)) {
            val data = org.json.JSONObject().put("id", "job-1").put("status", "in_progress").put("progress", n)
            reply(200, data.toString())
            assertEquals(n.toDouble(), c.get(task("job-1")).progress!!, 0.0)
        }
        val content = listOf("WEBVTT", "", "<v A>é", "").joinToString(13.toChar().toString() + 10.toChar())
        val data = org.json.JSONObject().put("id", "job-1").put("status", "completed").put("output", content)
        reply(200, data.toString())
        assertArrayEquals(content.toByteArray(), c.get(task("job-1")).output)
    }
    @Test fun untrustedCancellationIsSanitized() = runBlocking {
        val c = UrlTranscriptionClient(creds, { throw CancellationException("synthetic-secret") }, { url ->
            FakeConnection(url).also { connections += it }
        })
        try { c.submit(task()); fail() } catch (e: TranscriptionNetworkException) { assertSafeError(e) }
        assertEquals(1, connections.size)
    }

}
