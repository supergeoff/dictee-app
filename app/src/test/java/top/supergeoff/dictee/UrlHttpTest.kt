package top.supergeoff.dictee

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class UrlHttpTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @Volatile
    private var contentLength = ""

    @Volatile
    private var received = ByteArray(0)

    @After
    fun stopServer() {
        server.close()
    }

    /** Serveur minimal : lit les en-têtes puis le corps annoncé par Content-Length, répond 200 avec la taille reçue. */
    private fun serveOnce(): Thread = thread {
        server.accept().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val headers = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            contentLength = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(":").trim()
            received = ByteArray(contentLength.toInt()).also { input.readFully(it) }
            val reply = """{"size":"${received.size}"}"""
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply".toByteArray())
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }

    @Test
    fun `un corps en flux part par blocs de 64 Kio avec sa taille et signale la progression`() {
        val data = ByteArray(150 * 1024) { (it % 251).toByte() }
        val sent = mutableListOf<Long>()
        val body = HttpBody.Stream(data.size.toLong(), { data.inputStream() }, "audio/mpeg", { sent += it })
        val serving = serveOnce()
        val response = UrlHttp().call(HttpRequest("PUT", "http://127.0.0.1:${server.localPort}/upload", emptyMap(), body))
        serving.join(5_000)
        assertEquals(200, response.code)
        assertEquals(data.size.toString(), contentLength)
        assertTrue(data.contentEquals(received))
        assertEquals(listOf(65_536L, 131_072L, 153_600L), sent)
    }
}
