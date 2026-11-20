package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class JournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = LocalDateTime.of(2026, 10, 31, 10, 29, 40)
    private fun journal() = Journal(tmp.root) { now }

    @Test
    fun `une ligne a la forme heure source message dans le fichier du jour`() {
        journal().add(Source.BLE, "connexion à AA:BB:CC:DD:EE:FF")
        assertEquals("10:29:40 BLE connexion à AA:BB:CC:DD:EE:FF\n", File(tmp.root, "journal-2026-10-31.txt").readText())
    }

    @Test
    fun `une erreur est marquee et se filtre`() {
        val j = journal(); j.add(Source.DRIVE, "Dictée : 3 fichiers"); j.error(Source.DRIVE, "Drive a répondu 403")
        val errors = j.lines().filter { JournalFilter.ERRORS.accepts(it) }
        assertEquals(listOf("erreur : Drive a répondu 403"), errors.map { it.message })
        assertEquals(2, j.lines().count { JournalFilter.DRIVE.accepts(it) })
    }

    @Test
    fun `minuit ouvre un nouveau fichier`() {
        val j = journal(); now = LocalDateTime.of(2026, 10, 30, 23, 59, 59); j.add(Source.APP, "a")
        now = LocalDateTime.of(2026, 10, 31, 0, 0, 1); j.add(Source.APP, "b")
        assertEquals(listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31)), j.lines().map { it.date })
    }

    @Test
    fun `la purge supprime les fichiers d'avant hier`() {
        for (d in 23..31) File(tmp.root, "journal-2026-10-%02d.txt".format(d)).writeText("10:00:00 APP x\n")
        journal().purge()
        assertEquals(listOf("journal-2026-10-31.txt"), tmp.root.list()!!.sorted())
    }

    @Test
    fun `la purge retire les lignes de plus de huit heures`() {
        now = LocalDateTime.of(2026, 10, 31, 4, 0, 0)
        File(tmp.root, "journal-2026-10-30.txt").writeText("19:59:59 APP vieille\n20:00:00 APP gardee\n")
        File(tmp.root, "journal-2026-10-31.txt").writeText("03:00:00 APP recente\n")
        journal().purge()
        assertEquals("20:00:00 APP gardee\n", File(tmp.root, "journal-2026-10-30.txt").readText())
        assertEquals("03:00:00 APP recente\n", File(tmp.root, "journal-2026-10-31.txt").readText())
    }

    @Test
    fun `un fichier sans ligne recente est supprime`() {
        File(tmp.root, "journal-2026-10-31.txt").writeText("01:00:00 APP vieille\n")
        journal().purge()
        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test
    fun `la lecture et l'export ignorent les lignes de plus de huit heures`() {
        File(tmp.root, "journal-2026-10-31.txt").writeText("01:00:00 APP vieille\n09:00:00 APP recente\n")
        val j = journal()
        assertEquals(listOf("recente"), j.lines().map { it.message })
        assertEquals("== 2026-10-31 ==\n09:00:00 APP recente\n", j.exportText())
    }

    @Test
    fun `export et vidage`() {
        val j = journal(); j.add(Source.APP, "dictee 0.2.0 sur Android 15")
        assertTrue(j.exportText().contains("== 2026-10-31 ==\n10:29:40 APP dictee 0.2.0 sur Android 15"))
        j.clear(); assertTrue(j.lines().isEmpty())
    }

    @Test
    fun `la premiere ligne cree le dossier du journal`() {
        val dir = File(tmp.root, "journal")
        Journal(dir) { now }.add(Source.APP, "premiere ligne")
        assertEquals("10:29:40 APP premiere ligne\n", File(dir, "journal-2026-10-31.txt").readText())
    }

    @Test
    fun `un dossier impossible a ecrire ne fait lever ni add ni error, la ligne est perdue`() {
        val blocked = tmp.newFile("journal")   // un fichier à la place du dossier : ni mkdirs ni appendText ne peuvent réussir
        val j = Journal(blocked) { now }
        j.add(Source.APP, "perdue")
        j.error(Source.DRIVE, "perdue aussi")
        assertTrue(blocked.isFile)
        assertTrue(j.lines().isEmpty())
        assertEquals(0L, j.revision.value)
    }

    @Test
    fun `une requete HTTP est journalisee sans en-tetes, parametres ni corps`() {
        val lines = mutableListOf<String>()
        var t = 0L
        val http = LoggingHttp(FakeHttp(HttpResponse(200, "0123456789", emptyMap())), { lines += it }) { t.also { t += 5_000_000 } }
        http.call(HttpRequest("GET", "https://www.googleapis.com/drive/v3/files?q=secret", mapOf("Authorization" to "Bearer jeton")))
        assertEquals(listOf("GET /drive/v3/files 200 · 5 ms · 10 o"), lines)
    }
}
