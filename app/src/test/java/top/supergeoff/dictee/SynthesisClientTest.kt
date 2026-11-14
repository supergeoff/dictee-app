package top.supergeoff.dictee

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private class SynthesisFakeCredentials : CredentialStore {
    val refs = mutableListOf<String>()
    var failure: Exception? = null
    override fun read(ref: String): String { refs += ref; failure?.let { throw it }; return "synthetic-secret" }
    override fun save(secret: String) = "ref"
    override fun remove(ref: String) {}
}

class SynthesisClientTest {
    private val credentials = SynthesisFakeCredentials()
    private val connections = mutableListOf<FakeConnection>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val vtt = "WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nÉléphant synthétique\r\n".toByteArray(Charsets.UTF_8)
    private val context = SynthesisContext("https://example.invalid/v1", "frozen-ref", "frozen-model", "template", PromptMetadata("réunion", "date", "durée"), "Prompt résolu synthétique", "synthetic-hash")
    private fun response(content: Any = "Résumé é\n", reason: Any? = "stop", role: String = "assistant", refusal: Any? = null): String {
        val message = JSONObject().put("role", role).put("content", content)
        if (refusal != null) message.put("refusal", refusal)
        val choice = JSONObject().put("message", message)
        if (reason != null) choice.put("finish_reason", reason)
        return JSONObject().put("choices", JSONArray().put(choice)).toString()
    }
    private fun reply(status: Int = 200, body: String = response()) { replies.add(status to body) }
    private fun client(timeout: Long = 3000) = UrlSynthesisClient(credentials, { url ->
        val (status, body) = replies.removeFirst()
        FakeConnection(url, status, body.toByteArray(Charsets.UTF_8)).also { connections += it }
    }, timeout)
    private fun assertSafe(e: Throwable) {
        val stack = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        listOf("synthetic-secret", "Éléphant synthétique", "Prompt résolu synthétique", "provider-sensitive").forEach { assertFalse(stack.contains(it)) }
        var cause: Throwable? = e
        while (cause != null) {
            assertTrue(cause is SynthesisClientException)
            assertTrue(cause.suppressed.isEmpty())
            cause = cause.cause
        }
    }
    private suspend fun expect(kind: SynthesisFailureKind, block: suspend () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: SynthesisClientException) { assertEquals(kind, e.kind); assertSafe(e) }
    }

    @Test fun chatSendsFrozenSystemAndCompleteVtt() = runBlocking {
        reply()
        val client = client()
        assertTrue(credentials.refs.isEmpty())
        assertArrayEquals("Résumé é\n".toByteArray(), client.complete(context, vtt))
        val c = connections.single()
        assertEquals("https://example.invalid/v1/chat/completions", c.url.toString())
        assertEquals("POST", c.requestMethod)
        assertEquals("Bearer synthetic-secret", c.getRequestProperty("Authorization"))
        assertEquals("application/json", c.getRequestProperty("Content-Type"))
        val request = JSONObject(c.written.toString(Charsets.UTF_8.name()))
        assertEquals("frozen-model", request.getString("model"))
        val messages = request.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(context.systemPrompt, messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(vtt.toString(Charsets.UTF_8), messages.getJSONObject(1).getString("content"))
        assertEquals(listOf("frozen-ref"), credentials.refs)
        withTimeout(1000) { while (!c.closed) delay(5) }
        reply(500, "provider-sensitive synthetic-secret Éléphant synthétique")
        expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
    }

    @Test fun onlyNonEmptyUnrefusedUntruncatedAssistantTextIsAccepted() = runBlocking {
        val client = client()
        for (reason in listOf<Any?>("stop", null, JSONObject.NULL)) {
            reply(body = response("  Résumé é\r\n", reason))
            assertArrayEquals("  Résumé é\r\n".toByteArray(), client.complete(context, vtt))
        }
        val withToolCalls = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject()
            .put("role", "assistant").put("content", "contenu").put("tool_calls", JSONArray().put(JSONObject()))))).toString()
        val invalid = listOf("{}", "{\"choices\":[]}", response(""), response(" \n"), response(JSONObject.NULL), response(JSONArray().put("text")),
            response("contenu", role = "user"), response("contenu", refusal = "refus"), withToolCalls, response("contenu", "length"),
            response("contenu", "content_filter"), response("contenu", "tool_calls"), response("contenu", "unknown"),
            "not json")
        for (body in invalid) { reply(body = body); expect(SynthesisFailureKind.INVALID_RESPONSE) { client.complete(context, vtt) } }
    }

    @Test fun statusClassification() = runBlocking {
        val client = client()
        for (code in listOf(429, 500, 503)) { reply(code, "provider-sensitive"); expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) } }
        for (code in listOf(400, 401, 403, 404, 413)) { reply(code, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"provider-sensitive\"}}"); expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) } }
        reply(429, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"provider-sensitive\"}}")
        expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
        for (code in listOf(429, 500, 503)) {
            val longMessage = "x".repeat(17_000)
            reply(code, "{\"error\":{\"message\":\"$longMessage\",\"code\":\"context_length_exceeded\"}}")
            expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
        }
        val longMessage = "x".repeat(17_000)
        for (body in listOf(
            "{\"error\":{\"message\":\"$longMessage\",\"code\":\"context_length_exceeded\"",
            "{\"error\":{\"message\":\"$longMessage\\\"code\\\":\\\"context_length_exceeded\"}}",
            "{\"other\":{\"code\":\"context_length_exceeded\"},\"error\":{\"message\":\"$longMessage\"}}",
            "{\"error\":{\"message\":\"$longMessage\",\"code\":\"context_length_exceeded\",\"code\":\"other\"}}",
            "{\"error\":{\"code\":\"context_length_exceeded\"},\"error\":null}",
            "{\"error\":{\"message\":\"$longMessage\",\"code\":\"context_length_exceeded-extra\"}}",
            "{\"error\":{\"message\":\"$longMessage\",\"code\":12e+}}",
        )) { reply(429, body); expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) } }
        reply(429, "{\"other\":[true,false,null,-12.5e+3,{\"nested\":[]}],\"error\":{\"message\":\"$longMessage\",\"co\\u0064e\":\"context_length_exceed\\u0065d\"}}")
        expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
        val broken = UrlSynthesisClient(credentials, { throw IOException("provider-sensitive synthetic-secret") })
        expect(SynthesisFailureKind.TRANSIENT) { broken.complete(context, vtt) }
    }

    @Test fun testUsesFormValuesAndSafeErrors() = runBlocking {
        val client = client()
        val settings = SynthesisSettings("https://example.invalid/v1", "form-model", "ignored-ref", "form prompt")
        reply(); client.test(settings, "form-secret")
        val c = connections.single()
        assertEquals("Bearer form-secret", c.getRequestProperty("Authorization"))
        val request = JSONObject(c.written.toString(Charsets.UTF_8.name()))
        assertEquals("form-model", request.getString("model"))
        assertEquals("form prompt", request.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(c.written.toString(Charsets.UTF_8.name()).contains(vtt.toString(Charsets.UTF_8)))
        assertTrue(credentials.refs.isEmpty())
        reply(401, "provider-sensitive")
        expect(SynthesisFailureKind.PERMANENT) { client.test(settings, "form-secret") }
    }

    @Test fun incompleteContextAndCredentialFailureAvoidNetwork() = runBlocking {
        val client = client()
        for (bad in listOf(context.copy(systemPrompt = null), context.copy(systemPrompt = ""), context.copy(vttSha256 = null), context.copy(vttSha256 = ""))) {
            expect(SynthesisFailureKind.PERMANENT) { client.complete(bad, vtt) }
        }
        credentials.failure = IOException("synthetic-secret provider-sensitive")
        expect(SynthesisFailureKind.CREDENTIAL) { client.complete(context, vtt) }
        assertTrue(connections.isEmpty())
    }

    @Test fun untrustedErrorsAreSanitizedAndInvalidInputNeverReachesNetwork() = runBlocking {
        for (phase in listOf("connection", "write", "read", "close")) {
            val providerError = IOException("provider-sensitive synthetic-secret Éléphant synthétique").apply {
                initCause(IOException("provider-sensitive")); addSuppressed(IOException("synthetic-secret"))
            }
            val client = UrlSynthesisClient(credentials, { url ->
                if (phase == "connection") throw providerError
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int { if (phase == "read") throw providerError; return 200 }
                    override fun getInputStream(): InputStream = ByteArrayInputStream(response().toByteArray())
                    override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (phase == "write") throw providerError
                            super.write(b, off, len)
                        }
                        override fun close() { if (phase == "close") throw providerError }
                    }
                }
            })
            expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
        }
        val client = client()
        for (bad in listOf(context.copy(baseUrl = "/v1"), context.copy(model = ""))) {
            expect(SynthesisFailureKind.PERMANENT) { client.complete(bad, vtt) }
        }
        expect(SynthesisFailureKind.PERMANENT) { client.complete(context, byteArrayOf(0xc3.toByte())) }
        expect(SynthesisFailureKind.CREDENTIAL) { client.test(SynthesisSettings("https://example.invalid/v1", "model"), "bad\nsecret") }
        assertTrue(connections.isEmpty())
    }

    @Test fun deeplyNestedErrorPreservesContextCodeAndValidatesEntireJson() = runBlocking {
        val client = client()
        val nested = "[".repeat(10_000) + "null" + "]".repeat(10_000)
        for (depth in listOf(64, 10_000)) {
            val arrays = "[".repeat(depth) + "null" + "]".repeat(depth)
            val objects = "{\"x\":".repeat(depth) + "null" + "}".repeat(depth)
            for (status in listOf(429, 500, 503)) for (value in listOf(arrays, objects)) {
                reply(status, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":$value}")
                expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
                reply(status, "{\"other\":$value,\"error\":{\"code\":\"context_length_exceeded\"}}")
                expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
            }
        }
        reply(429, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":$nested,\"error\":null}")
        expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
        reply(429, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":${nested.dropLast(1)}}")
        expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
        val maxDepth = "[".repeat(65_536) + "null" + "]".repeat(65_536)
        reply(429, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":$maxDepth}")
        expect(SynthesisFailureKind.PERMANENT) { client.complete(context, vtt) }
        val tooDeep = "[".repeat(65_537) + "null" + "]".repeat(65_537)
        reply(429, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":$tooDeep}")
        expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
        for (invalid in listOf("[1,]", "{\"x\":1,}", "[{} {}]", "{\"x\":[] \"y\":1}")) {
            reply(429, "{\"error\":{\"code\":\"context_length_exceeded\"},\"other\":$invalid}")
            expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
        }
    }

    @Test fun cancellationStopsGeneratedErrorStream() = runBlocking { generatedErrorStreamStops(cancel = true) }

    @Test fun timeoutStopsGeneratedErrorStream() = runBlocking { generatedErrorStreamStops(cancel = false) }

    private suspend fun generatedErrorStreamStops(cancel: Boolean) = coroutineScope {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val workerDisconnected = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val stop = AtomicBoolean(false)
        var attempts = 0
        val client = UrlSynthesisClient(credentials, { url ->
            attempts++
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun usingProxy() = false
                override fun disconnect() {
                    if (Thread.currentThread() === worker.get()) workerDisconnected.countDown()
                }
                override fun getResponseCode() = 429
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getErrorStream(): InputStream = object : InputStream() {
                    private val prefix = "{\"error\":{\"message\":\"".toByteArray()
                    private var position = 0
                    override fun read(): Int {
                        worker.compareAndSet(null, Thread.currentThread())
                        entered.countDown()
                        if (stop.get()) return -1
                        return if (position < prefix.size) prefix[position++].toInt() else 'x'.code
                    }
                    override fun close() { closed.countDown() }
                }
            }
        }, if (cancel) 3000 else 500)
        try {
            if (cancel) {
                val job = launch { client.complete(context, vtt) }
                withContext(Dispatchers.IO) { assertTrue(entered.await(1, TimeUnit.SECONDS)) }
                job.cancelAndJoin()
            } else {
                expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
            }
            withContext(Dispatchers.IO) {
                assertTrue("error stream not closed", closed.await(1, TimeUnit.SECONDS))
                assertTrue("worker did not finish cleanup", workerDisconnected.await(1, TimeUnit.SECONDS))
            }
            assertEquals(1, attempts)
        } finally {
            stop.set(true)
        }
    }

    @Test fun cancellationAndTimeoutCloseConnectionWithoutRetry() = runBlocking {
        suspend fun run(cancel: Boolean) {
            val entered = CountDownLatch(1)
            lateinit var connection: FakeConnection
            var attempts = 0
            val client = UrlSynthesisClient(credentials, { url ->
                attempts++
                FakeConnection(url, 200, response().toByteArray()).also { c ->
                    connection = c
                    c.outputFactory = { object : OutputStream() {
                        override fun write(b: Int) { entered.countDown(); while (!c.closed) Thread.sleep(5) }
                    } }
                }
            }, if (cancel) 3000 else 150)
            if (cancel) {
                val job = launch { client.complete(context, vtt) }
                withContext(Dispatchers.IO) { assertTrue(entered.await(1, TimeUnit.SECONDS)) }
                job.cancelAndJoin()
            } else {
                expect(SynthesisFailureKind.TRANSIENT) { client.complete(context, vtt) }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
            }
            withTimeout(1000) { while (!connection.closed) delay(5) }
            assertEquals(1, attempts)
        }
        run(true); run(false)
    }
}
