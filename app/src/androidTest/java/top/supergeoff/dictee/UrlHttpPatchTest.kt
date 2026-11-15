package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class UrlHttpPatchTest {
    @Test fun patchReachesLocalServerWithBody() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            var requestLine: String? = null
            var received = ""
            val worker = thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        requestLine = input.readLine()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) { val n = input.read(chars, read, length - read); if (n < 0) break; read += n }
                        received = String(chars, 0, read)
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                    }
                } catch (_: java.net.SocketTimeoutException) { }
                catch (_: java.net.SocketException) { }
            }
            try {
                val response = UrlHttp().call(HttpRequest("PATCH", "http://127.0.0.1:${server.localPort}/upload", emptyMap(), HttpBody.Bytes("synthetic".toByteArray(), "text/plain")))
                worker.join(6000)
                assertEquals(200, response.code)
                assertEquals("PATCH /upload HTTP/1.1", requestLine)
                assertEquals("synthetic", received)
            } finally {
                server.close()
                worker.join(6000)
            }
        }
    }
}
