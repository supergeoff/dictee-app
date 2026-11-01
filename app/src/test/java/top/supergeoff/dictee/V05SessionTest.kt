package top.supergeoff.dictee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/** Fausse liaison : chaque trame écrite est confiée au script, qui pousse les notifications du boîtier. */
class FakeLink(private val script: FakeLink.(cmd: Int, payload: ByteArray) -> Unit) : Link {
    private val channel = Channel<Notification>(Channel.UNLIMITED)
    override val incoming get() = channel
    val written = mutableListOf<ByteArray>()

    override suspend fun write(frame: ByteArray) {
        written += frame
        script(frame[3].toInt() and 0xff, frame.copyOfRange(5, frame.size - 2))
    }

    override suspend fun close() {
        channel.close()
    }

    fun reply(frameHex: String) {
        channel.trySend(Notification(LinkChannel.COMMAND, hex(frameHex), "0011203a"))
    }

    fun reply(cmd: Int, payload: ByteArray) {
        // Trame du boîtier : CRC en little-endian.
        val be = Frame.build(cmd, payload)
        val crc = Frame.crc16(be, be.size - 2)
        be[be.size - 2] = crc.toByte()
        be[be.size - 1] = (crc ushr 8).toByte()
        channel.trySend(Notification(LinkChannel.COMMAND, be, "0011203a"))
    }

    /** Audio d'un fichier, sur la caractéristique B0B4, en paquets de 244 octets, comme observé sur le boîtier. */
    fun audio(bytes: ByteArray) {
        bytes.toList().chunked(244).forEach {
            channel.trySend(Notification(LinkChannel.FILE_AUDIO, it.toByteArray(), "0011204a"))
        }
    }

    /** Audio en direct, sur la caractéristique B0B3. */
    fun liveAudio(bytes: ByteArray) {
        channel.trySend(Notification(LinkChannel.RECORD_AUDIO, bytes, "0011201a"))
    }

    fun disconnect() {
        channel.close()
    }

    fun sentPayloads(cmd: Int): List<ByteArray> =
        written.filter { (it[3].toInt() and 0xff) == cmd }.map { it.copyOfRange(5, it.size - 2) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class V05SessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stream: ByteArray =
        V05SessionTest::class.java.getResource("/stream.bin")!!.readBytes()
    private val name = "20260101120000"
    private val frames = sessionFrames().filter { it.first == "RX" }.map { it.second.toHex() }
    private fun rx(prefix: String) = frames.first { it.startsWith(prefix) }

    /** Réponses de frames.txt aux commandes de la poignée de main et de la liste. */
    private fun FakeLink.sessionReplies(cmd: Int): Boolean {
        when (cmd) {
            Cmd.BIND_READ -> reply(rx("a00a0102"))
            Cmd.BIND -> reply(rx("a00a0103"))
            Cmd.SERIAL -> reply(rx("a00a0101"))
            Cmd.INFO -> reply(rx("a00a0105"))
            Cmd.TIME -> reply(rx("a00a0104"))
            Cmd.BEFORE_LIST -> reply(rx("a00a0118"))
            Cmd.LIST -> frames.filter { it.startsWith("a00a010a") }.forEach { reply(it) }
            else -> return false
        }
        return true
    }

    /** Envoi d'un fichier comme le boîtier l'a fait avec 0x0b : statut et taille, audio, puis 02. */
    private fun FakeLink.sendFile(bytes: ByteArray, announced: Long = bytes.size.toLong()) {
        reply(Cmd.FILE, byteArrayOf(0) + u32(announced))
        audio(bytes)
        reply(Cmd.FILE, byteArrayOf(Payloads.FILE_END.toByte()))
    }

    /** Boîtier qui n'envoie que `accepted` et refuse tout le reste avec le statut 01. */
    private fun FakeLink.onlySends(accepted: String, cmd: Int, payload: ByteArray) {
        if (cmd != Cmd.FILE) return
        if (String(payload.copyOfRange(0, 14), Charsets.US_ASCII) == accepted) sendFile(stream) else reply(Cmd.FILE, byteArrayOf(1))
    }

    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun TestScope.session(link: Link, journal: MutableList<String> = mutableListOf()) =
        V05Session(link, journal = { journal += it }, clock = { LocalDateTime.of(2026, 1, 2, 12, 34, 56) })

    private fun dir(): File = tmp.newFolder()

    @Test
    fun `la poignee de main rejoue l'appairage et met le boitier a l'heure`() = runTest {
        val link = FakeLink { cmd, _ -> sessionReplies(cmd) }
        session(link).handshake()
        assertEquals(
            listOf(Cmd.BIND_READ, Cmd.BIND, Cmd.SERIAL, Cmd.INFO, Cmd.TIME),
            link.written.map { it[3].toInt() and 0xff },
        )
        assertEquals("a00a0103113132333435360000000000000000000000c2bf", link.written[1].toHex())
        assertEquals("a00a0104061a01020c223872af", link.written[4].toHex())
    }

    @Test
    fun `un appairage refuse arrete la session`() = runTest {
        val link = FakeLink { cmd, _ ->
            if (cmd == Cmd.BIND) reply(Cmd.BIND, byteArrayOf(1)) else sessionReplies(cmd)
        }
        try {
            session(link).handshake()
            fail("exception attendue")
        } catch (e: SessionException) {
            assertEquals("appairage refusé", e.message)
        }
    }

    @Test
    fun `une commande sans reponse echoue apres 5 secondes`() = runTest {
        val link = FakeLink { _, _ -> }
        try {
            session(link).handshake()
            fail("exception attendue")
        } catch (e: SessionException) {
            assertEquals(5_000L, currentTime)
            assertTrue(e.message!!.contains("0x02"))
        }
    }

    @Test
    fun `la liste suit 0x18 avec 00 comme observe et donne les enregistrements`() = runTest {
        val link = FakeLink { cmd, _ -> sessionReplies(cmd) }
        val list = session(link).listRecords()
        assertEquals(listOf("a00a01180100ca00", "a00a010a00a1d4"), link.written.map { it.toHex() })
        assertEquals(listOf("20260101090000", "20260101140000", "20260101103000"), list.map { it.name })
        assertEquals(1234572L, list[2].size)
    }

    @Test
    fun `le transfert rejoue les trames 0x0b observees`() = runTest {
        val fileName = "20260101153000"
        // 76 644 octets, construits en répétant le flux MP3 de stream.bin.
        val bytes = (stream + stream + stream + stream).copyOfRange(0, 76644)
        val link = FakeLink { cmd, _ ->
            if (cmd == Cmd.FILE) {
                reply("a00a010b050000012b644da0")
                audio(bytes)
                reply("a00a010b010270ce")
            }
        }
        val out = dir()
        val result = session(link).download(fileName, 76644L, out) { _, _ -> }
        assertEquals("a00a010b123230323630313031313533303030000000006b8d", link.written.single().toHex())
        assertEquals(Download.Ok(fileName, File(out, "$fileName.mp3"), 76644L), result)
        assertArrayEquals(bytes, File(out, "$fileName.mp3").readBytes())
        assertEquals(listOf("$fileName.mp3"), out.list()!!.toList())
    }

    @Test
    fun `une coupure pendant le transfert supprime le fichier partiel`() = runTest {
        val link = FakeLink { cmd, _ ->
            if (cmd == Cmd.FILE) {
                reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong()))
                audio(stream.copyOfRange(0, 1440))
                disconnect()
            }
        }
        val out = dir()
        val result = session(link).download(name, 24480L, out) { _, _ -> }
        assertTrue(result is Download.Failed && result.fatal)
        assertEquals(0, out.list()!!.size)
    }

    @Test
    fun `un silence de 20 secondes interrompt le transfert`() = runTest {
        val link = FakeLink { cmd, _ ->
            if (cmd == Cmd.FILE) {
                reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong()))
                audio(stream.copyOfRange(0, 1440))
            }
        }
        val out = dir()
        val result = session(link).download(name, 24480L, out) { _, _ -> }
        assertTrue(result is Download.Failed && result.fatal)
        assertEquals(20_000L, currentTime)
        assertEquals(0, out.list()!!.size)
    }

    @Test
    fun `une absence de reponse a 0x0b echoue apres 5 secondes sans relance`() = runTest {
        val link = FakeLink { _, _ -> }
        val result = session(link).download(name, 24480L, dir()) { _, _ -> }
        assertTrue(result is Download.Failed && result.fatal)
        assertEquals(5_000L, currentTime)
        assertEquals(1, link.sentPayloads(Cmd.FILE).size)
    }

    @Test
    fun `une taille differente de celle annoncee est refusee sans arreter la synchro`() = runTest {
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) sendFile(stream, announced = 24481L) }
        val out = dir()
        val result = session(link).download(name, 24481L, out) { _, _ -> }
        assertTrue(result is Download.Failed && !result.fatal)
        assertEquals(0, out.list()!!.size)
    }

    @Test
    fun `un fichier sans en-tete MP3 est refuse`() = runTest {
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) sendFile(ByteArray(288)) }
        val out = dir()
        val result = session(link).download(name, 288L, out) { _, _ -> }
        assertTrue(result is Download.Failed && !result.fatal)
        assertEquals(0, out.list()!!.size)
    }

    @Test
    fun `un refus de 0x0b ne bloque pas la file et n'est pas relance`() = runTest {
        val link = FakeLink { cmd, payload -> onlySends(name, cmd, payload) }
        val entries = listOf(ListItem.Entry("20260101090000", 176596L), ListItem.Entry(name, 24480L))
        val results = mutableListOf<Download>()
        val returned = session(link).downloadAll(entries, dir(), onStart = { _, _, _ -> }, onProgress = { _, _, _ -> }) { results += it }
        assertEquals(results, returned)
        val refused = results[0] as Download.Failed
        assertTrue(refused.refused)
        assertFalse(refused.fatal)
        assertTrue(refused.reason, refused.reason.contains("statut 01"))
        assertTrue(results[1] is Download.Ok)
        assertEquals(2, link.sentPayloads(Cmd.FILE).size)
    }

    @Test
    fun `l'audio en direct est ignore pendant le transfert d'un fichier`() = runTest {
        val journal = mutableListOf<String>()
        val link = FakeLink { cmd, _ ->
            if (cmd == Cmd.FILE) {
                reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong()))
                liveAudio(ByteArray(144) { 7 })
                audio(stream)
                reply(Cmd.FILE, byteArrayOf(Payloads.FILE_END.toByte()))
            }
        }
        val out = dir()
        val result = session(link, journal).download(name, 24480L, out) { _, _ -> }
        assertEquals(Download.Ok(name, File(out, "$name.mp3"), 24480L), result)
        assertArrayEquals(stream, File(out, "$name.mp3").readBytes())
        assertTrue(journal.any { it.contains("144 octets") && it.contains("ignor") })
    }

    @Test
    fun `la progression suit les octets recus`() = runTest {
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) sendFile(stream) }
        val seen = mutableListOf<Pair<Long, Long>>()
        session(link).download(name, 24480L, dir()) { got, total -> seen += got to total }
        assertEquals(24480L to 24480L, seen.last())
        assertTrue(seen.size > 1)
    }

    @Test
    fun `une erreur fatale arrete la file`() = runTest {
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) disconnect() }
        val entries = listOf(ListItem.Entry("20260101090000", 176596L), ListItem.Entry("20260101140000", 27196L))
        val results = mutableListOf<Download>()
        session(link).downloadAll(entries, dir(), onStart = { _, _, _ -> }, onProgress = { _, _, _ -> }) { results += it }
        assertEquals(1, results.size)
        assertTrue((results[0] as Download.Failed).fatal)
    }

    @Test
    fun `le journal note les trames et le volume audio par caracteristique`() = runTest {
        val journal = mutableListOf<String>()
        val link = FakeLink { cmd, _ -> if (!sessionReplies(cmd) && cmd == Cmd.FILE) sendFile(stream) }
        val s = session(link, journal)
        s.handshake()
        s.download(name, 24480L, dir()) { _, _ -> }
        assertTrue(journal.any { it.startsWith("TX a00a01020061d3") })
        assertTrue(journal.any { it.startsWith("RX a00a0103010070cd") })
        assertTrue(journal.any { it.contains("24480 octets") && it.contains("0011204a") })
    }
}
