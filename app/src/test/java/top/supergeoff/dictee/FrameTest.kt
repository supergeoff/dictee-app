package top.supergeoff.dictee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Trames du fichier frames.txt, dans l'ordre : paires (sens, octets). */
fun sessionFrames(): List<Pair<String, ByteArray>> =
    FrameTest::class.java.getResource("/frames.txt")!!.readText()
        .lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split(" ").let { it[0] to hex(it[1]) } }

class FrameTest {

    @Test
    fun `le CRC-16 ARC donne les vecteurs de la spec`() {
        assertEquals(0x61d3, Frame.crc16(hex("a00a010200")))
        assertEquals(0xa1d4, Frame.crc16(hex("a00a010a00")))
        assertEquals(0xca00, Frame.crc16(hex("a00a01180100")))
        assertEquals(0xcd70, Frame.crc16(hex("a00a01030100")))
        assertEquals(0x747f, Frame.crc16(hex("a00a01071500323032363031303131323030303000005fa00101")))
    }

    @Test
    fun `les trames de l'application portent le CRC en big-endian`() {
        assertEquals("a00a01020061d3", Frame.build(Cmd.BIND_READ).toHex())
        assertEquals("a00a010a00a1d4", Frame.build(Cmd.LIST).toHex())
        assertEquals("a00a01150091dc", Frame.build(Cmd.LIVE_START).toHex())
        assertEquals("a00a01180100ca00", Frame.build(0x18, byteArrayOf(0)).toHex())
    }

    @Test
    fun `chaque trame observee vers le boitier se reconstruit a l'identique`() {
        val tx = sessionFrames().filter { it.first == "TX" }.map { it.second }
        assertEquals(8, tx.size)
        for (frame in tx) {
            val cmd = frame[3].toInt() and 0xff
            val payload = frame.copyOfRange(5, frame.size - 2)
            assertEquals(frame.toHex(), Frame.build(cmd, payload).toHex())
        }
    }

    @Test
    fun `chaque trame du boitier se lit avec le CRC en little-endian`() {
        val rx = sessionFrames().filter { it.first == "RX" }.map { it.second }
        assertEquals(13, rx.size)
        for (frame in rx) {
            val parsed = Frame.parse(frame)
            assertNotNull("trame refusée : ${frame.toHex()}", parsed)
            assertEquals(frame[3].toInt() and 0xff, parsed!!.cmd)
            assertEquals(frame[4].toInt() and 0xff, parsed.payload.size)
        }
    }

    @Test
    fun `une trame du boitier avec un CRC faux ou mal ordonne est refusee`() {
        assertNull(Frame.parse(hex("a00a01030100cd70")))
        assertNull(Frame.parse(hex("a00a01030100ffff")))
        assertNull(Frame.parse(hex("a00a010301")))
        assertNull(Frame.parse(hex("55aa01030100700d")))
        assertNull(Frame.parse(hex("a00a0103020070cd")))
    }

    @Test
    fun `la reponse 0x02 donne l'identifiant et la requete 0x03 le renvoie`() {
        val rsp = Frame.parse(hex("a00a010210313233343536000000000000000000000792"))!!
        assertEquals("123456", Payloads.bindIdText(rsp.payload))
        val req = Frame.build(Cmd.BIND, Payloads.bindRequest(rsp.payload))
        assertEquals("a00a0103113132333435360000000000000000000000c2bf", req.toHex())
    }

    @Test
    fun `la requete 0x04 code la date en six octets`() {
        val payload = Payloads.timeRequest(LocalDateTime.of(2026, 1, 2, 12, 34, 56))
        assertEquals("a00a0104061a01020c223872af", Frame.build(Cmd.TIME, payload).toHex())
    }

    @Test
    fun `la liste donne les noms et les tailles puis la fin`() {
        val rx = sessionFrames().filter { it.first == "RX" }.map { Frame.parse(it.second)!! }
            .filter { it.cmd == Cmd.LIST }
        val items = rx.map { Payloads.listItem(it.payload) }
        assertEquals(
            listOf(
                ListItem.Entry("20260101090000", 176596L, 0),
                ListItem.Entry("20260101140000", 27196L, 1),
                ListItem.Entry("20260101103000", 1234572L, 1),
                ListItem.End,
            ),
            items,
        )
    }

    @Test
    fun `une entree sans indicateur donne le flag -1`() {
        val payload = byteArrayOf(0) + "20260101090000".toByteArray(Charsets.US_ASCII) + hex("0002b1d4")
        assertEquals(19, payload.size)
        assertEquals(-1, (Payloads.listItem(payload) as ListItem.Entry).flag)
    }

    @Test
    fun `la reponse 0x15 annonce le fichier envoye`() {
        val rsp = Frame.parse(hex("a00a01151400323032363031303131323030303000000000015aa8"))!!
        assertEquals(LiveStart(status = 0, name = "20260101120000", size = 0L), Payloads.liveStart(rsp.payload))
    }

    @Test
    fun `la trame 0x07 annonce la taille transferee`() {
        val rsp = Frame.parse(hex("a00a01071500323032363031303131323030303000005fa001017f74"))!!
        assertEquals(LiveEnd(status = 0, name = "20260101120000", size = 24480L), Payloads.liveEnd(rsp.payload))
    }

    @Test
    fun `la trame 0x06 donne le nom du nouvel enregistrement`() {
        val rsp = Frame.parse(hex("a00a01061000323032363031303131323030303001fa97"))!!
        assertEquals("20260101120000", Payloads.newRecordName(rsp.payload))
    }

    @Test
    fun `le statut est le premier octet du payload`() {
        assertEquals(0, Payloads.status(Frame.parse(hex("a00a0103010070cd"))!!.payload))
        assertEquals(-1, Payloads.status(ByteArray(0)))
    }

    @Test
    fun `la requete 0x0b porte le nom puis la position sur quatre octets`() {
        val payload = Payloads.fileRequest("20260101103000", 0L)
        assertEquals(18, payload.size)
        assertArrayEquals("20260101103000".toByteArray(Charsets.US_ASCII), payload.copyOfRange(0, 14))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), payload.copyOfRange(14, 18))
        assertEquals("000102", Payloads.fileRequest("20260101103000", 258L).copyOfRange(15, 18).toHex())
    }

    @Test
    fun `les trames 0x0b observees se reconstruisent et se lisent`() {
        val request = Frame.build(Cmd.FILE, Payloads.fileRequest("20260101153000", 0L))
        assertEquals("a00a010b123230323630313031313533303030000000006b8d", request.toHex())

        val start = Frame.parse(hex("a00a010b050000012b644da0"))!!
        assertEquals(Cmd.FILE, start.cmd)
        assertEquals(0, Payloads.status(start.payload))
        assertEquals(76644L, Payloads.fileSize(start.payload))

        val end = Frame.parse(hex("a00a010b010270ce"))!!
        assertEquals(Payloads.FILE_END, Payloads.status(end.payload))
        assertNull(Payloads.fileSize(end.payload))
    }

    @Test
    fun `l'en-tete MP3 est reconnu`() {
        assertTrue(Payloads.isMp3Header(hex("fff348c4")))
        assertTrue(Payloads.isMp3Header(hex("fffb")))
        assertFalse(Payloads.isMp3Header(hex("fff")))
        assertFalse(Payloads.isMp3Header(hex("4f67")))
        assertFalse(Payloads.isMp3Header(ByteArray(0)))
    }
}
