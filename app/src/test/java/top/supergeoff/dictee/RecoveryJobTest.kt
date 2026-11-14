package top.supergeoff.dictee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryJobTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stream = javaClass.getResource("/stream.bin")!!.readBytes()
    private val a = "20260101120000"
    private val b = "20260101153000"
    private fun entry(name: String, flag: Int = 0) = ListItem.Entry(name, stream.size.toLong(), flag)
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun FakeLink.sendFile() { reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong())); audio(stream); reply(Cmd.FILE, byteArrayOf(2)) }
    private fun sending() = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) sendFile() }
    private fun job(link: FakeLink, store: PhoneStore, cache: File = tmp.newFolder(), log: ListLog = ListLog()) =
        RecoveryJob(V05Session(link, { log.add(Source.BLE, it) }), store, cache, log)

    @Test
    fun `l'enregistrement arrive prefixe dans le dossier et le cache se vide, reste ancien compris`() = runTest {
        val store = MemoryStore(); val cache = tmp.newFolder()
        File(cache, "20260101103000.mp3.part").writeText("reste d'une récupération interrompue")
        val results = job(sending(), store, cache).run(listOf(entry(a)), { _, _ -> }, {})
        assertEquals(listOf(RecoveryResult.Ok(a, "Call-$a.mp3", stream.size.toLong())), results)
        assertArrayEquals(stream, store.files.getValue("Call-$a.mp3")); assertTrue(cache.list()!!.isEmpty())
    }

    @Test
    fun `une nouvelle recuperation remplace le fichier du meme nom`() = runTest {
        val store = MemoryStore().apply { files["Call-$a.mp3"] = byteArrayOf(1, 2, 3) }
        job(sending(), store).run(listOf(entry(a)), { _, _ -> }, {})
        assertEquals(listOf("Call-$a.mp3"), store.files.keys.toList()); assertArrayEquals(stream, store.files.getValue("Call-$a.mp3"))
    }

    @Test
    fun `un indicateur inconnu donne un fichier Note et une ligne au journal`() = runTest {
        val store = MemoryStore(); val log = ListLog()
        job(sending(), store, log = log).run(listOf(entry(a, flag = 7)), { _, _ -> }, {})
        assertEquals(setOf("Note-$a.mp3"), store.files.keys); assertTrue(log.lines.any { it.contains("indicateur 07 inconnu") })
    }

    @Test
    fun `un dossier inaccessible arrete la file et vide le cache`() = runTest {
        val link = sending(); val cache = tmp.newFolder()
        val results = job(link, MemoryStore(failWrites = true), cache).run(listOf(entry(a), entry(b)), { _, _ -> }, {})
        assertEquals(1, results.size); assertTrue((results.single() as RecoveryResult.Failed).stopsQueue)
        assertEquals(1, link.sentPayloads(Cmd.FILE).size); assertTrue(cache.list()!!.isEmpty())
    }

    @Test
    fun `la cause technique d'un echec d'ecriture va au journal, pas a la ligne de l'enregistrement`() = runTest {
        val store = object : PhoneStore {
            override fun list() = emptyList<PhoneFile>()
            override fun write(fileName: String, source: File) {
                throw PhoneStoreException("Dossier du téléphone inaccessible.", FileNotFoundException("dossier illisible"))
            }
            override fun writeGuarded(fileName: String, source: File, replace: Boolean, promote: ((() -> Unit) -> Unit)) { throw PhoneStoreException("Non utilisé") }
            override fun open(fileName: String): InputStream = throw PhoneStoreException("absent")
        }
        val log = ListLog()
        val failed = job(sending(), store, log = log).run(listOf(entry(a)), { _, _ -> }, {}).single() as RecoveryResult.Failed
        assertEquals("Dossier du téléphone inaccessible.", failed.reason)
        assertTrue(log.lines.contains("FS erreur : Dossier du téléphone inaccessible. (cause : FileNotFoundException : dossier illisible)"))
    }

    @Test
    fun `une coupure arrete la file et un refus la laisse continuer`() = runTest {
        val cut = FakeLink { cmd, _ -> if (cmd == Cmd.FILE) { reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong())); audio(stream.copyOf(1000)); disconnect() } }
        val r1 = job(cut, MemoryStore()).run(listOf(entry(a), entry(b)), { _, _ -> }, {})
        assertEquals(1, r1.size); assertTrue((r1.single() as RecoveryResult.Failed).stopsQueue)
        val refusing = FakeLink { cmd, payload ->
            if (cmd == Cmd.FILE) { if (String(payload.copyOfRange(0, 14), Charsets.US_ASCII) == a) reply(Cmd.FILE, byteArrayOf(1)) else sendFile() }
        }
        val store = MemoryStore()
        val r2 = job(refusing, store).run(listOf(entry(a), entry(b)), { _, _ -> }, {})
        assertEquals(listOf(false, true), r2.map { it is RecoveryResult.Ok }); assertEquals(setOf("Call-$b.mp3"), store.files.keys)
    }

    @Test
    fun `l'avancement compte la file et suit les octets`() = runTest {
        val labels = mutableListOf<String>()
        job(sending(), MemoryStore()).run(listOf(entry(b), entry(a)), { _, p -> labels += p.label() }, {})
        assertEquals("Récupération 1/2 · 0 %", labels.first()); assertEquals("Récupération 2/2 · 100 %", labels.last())
    }
}
