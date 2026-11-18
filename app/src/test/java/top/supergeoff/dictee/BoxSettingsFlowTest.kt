package top.supergeoff.dictee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoxSettingsFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val address = "AA:BB:CC:DD:EE:FF"

    private fun file() = File(tmp.root, "state.json")

    /** Payload d'état de 52 octets : batterie 100, capacités nulles, réglages aux index 39, 41, 42. */
    private fun statusPayload(noise: Int = 0, segment: Int = 0, backlight: Int = 0): ByteArray =
        ByteArray(BoxStatus.PAYLOAD_LENGTH).also {
            it[9] = 100
            it[39] = noise.toByte()
            it[41] = segment.toByte()
            it[42] = backlight.toByte()
        }

    @Test fun `l enregistrement ecrit la lecture seulement pour l adresse associee`() = runTest {
        val repo = LocalStateRepository(LocalStateStore(file()))
        repo.updateDurably { it.copy(deviceAddress = address) }
        val reading = BoxReading(address, BoxStatus(71, 100, 50, 9, 2, 1))
        val saved = recordBoxReading(repo, reading)
        assertEquals(reading.status, saved.boxStatus)
        assertEquals(reading.status, LocalStateStore(file()).load().boxStatus)
    }

    @Test fun `une lecture d ancienne adresse est ignoree`() = runTest {
        val store = LocalStateStore(file())
        val avant = LocalState(deviceAddress = "11:22:33:44:55:66", boxStatus = BoxStatus(10, 1, 1, 0, 0, 0))
        store.save(avant)
        val repo = LocalStateRepository(store)
        val current = recordBoxReading(repo, BoxReading("99:88:77:66:55:44", BoxStatus(99, 9, 9, 9, 9, 9)))
        assertEquals(avant.boxStatus, current.boxStatus)
        assertEquals(avant, store.load())
    }

    @Test fun `un echec de sauvegarde de la lecture est propage`() = runTest {
        LocalStateStore(file()).save(LocalState(deviceAddress = address))
        val store = LocalStateStore(file(), rename = { _, _ -> false })
        val repo = LocalStateRepository(store)
        try {
            recordBoxReading(repo, BoxReading(address, BoxStatus(1, 1, 1, 1, 1, 1)))
            fail("l'échec de sauvegarde doit remonter")
        } catch (_: Exception) { }
        assertEquals(address, repo.awaitInitialized().deviceAddress)
        assertNull(LocalStateStore(file()).load().boxStatus)
    }

    @Test fun `la fraicheur est perdue hors ligne ou sur nouvelle adresse`() {
        assertTrue(isBoxStatusFresh(address, address, LinkStatus.CONNECTED))
        assertFalse(isBoxStatusFresh(address, address, LinkStatus.OFFLINE))
        assertFalse(isBoxStatusFresh(address, address, LinkStatus.CONNECTING))
        assertFalse(isBoxStatusFresh(address, "11:22:33:44:55:66", LinkStatus.CONNECTED))
        assertFalse(isBoxStatusFresh(null, address, LinkStatus.CONNECTED))
        assertFalse(isBoxStatusFresh(address, null, LinkStatus.CONNECTED))
    }

    @Test fun `un reglage accepte et relu confirme son application`() {
        val setting = BoxSetting.Noise(NoiseReduction.MOYEN)
        val relu = BoxStatus(100, 0, 0, noise = 9, segmentDuration = 2, backlight = 1)
        assertEquals("Réglage appliqué et relu.", boxSettingMessage(setting, BoxSettingResult(true, relu)))
    }

    @Test fun `un refus du boitier est signale meme si la relecture concorde`() {
        val setting = BoxSetting.Noise(NoiseReduction.MOYEN)
        val relu = BoxStatus(100, 0, 0, noise = 9, segmentDuration = 0, backlight = 0)
        assertEquals("Le boîtier a refusé ce réglage.", boxSettingMessage(setting, BoxSettingResult(false, relu)))
        val autre = relu.copy(noise = 5)
        assertEquals("Le boîtier a refusé ce réglage.", boxSettingMessage(setting, BoxSettingResult(false, autre)))
    }

    @Test fun `une divergence de relecture est signalee`() {
        val setting = BoxSetting.Noise(NoiseReduction.MOYEN)
        val divergent = BoxStatus(100, 0, 0, noise = 5, segmentDuration = 0, backlight = 0)
        assertEquals("Réglage non appliqué. La valeur relue est affichée.", boxSettingMessage(setting, BoxSettingResult(true, divergent)))
    }

    @Test fun `une relecture divergente publie la valeur reelle`() = runTest {
        // Le boîtier acquiesce mais garde « Faible » alors que « Moyen » est demandé.
        val link = FakeLink { cmd, _ ->
            when (cmd) {
                Cmd.NOISE_REDUCTION -> reply(cmd, byteArrayOf(0))
                Cmd.INFO -> reply(Cmd.INFO, statusPayload(noise = 5))
            }
        }
        val session = V05Session(link, {})
        val messages = mutableListOf<String>()
        val published = mutableListOf<BoxStatus>()
        val setting = BoxSetting.Noise(NoiseReduction.MOYEN)
        deliverBoxSetting(setting, send = { demande ->
            val result = session.writeSetting(demande)
            published += result.status
            result
        }, onMessage = { messages += it })
        assertEquals(listOf("Réglage non appliqué. La valeur relue est affichée."), messages)
        assertEquals(listOf(BoxStatus(100, 0L, 0L, 5, 0, 0)), published)
    }

    @Test fun `une coupure ne conserve aucune intention a rejouer`() = runTest {
        val store = LocalStateStore(file())
        val avant = LocalState(deviceAddress = address, boxStatus = BoxStatus(71, 100, 50, 9, 2, 1))
        store.save(avant)
        val repo = LocalStateRepository(store)
        repo.awaitInitialized()
        val link = FakeLink { cmd, _ -> if (cmd == Cmd.NOISE_REDUCTION) disconnect() }
        val session = V05Session(link, {})
        val messages = mutableListOf<String>()
        deliverBoxSetting(BoxSetting.Noise(NoiseReduction.ELEVE), send = { session.writeSetting(it) }, onMessage = { messages += it })
        assertEquals(listOf("Réglage non vérifié. Reconnecte le boîtier avant de réessayer."), messages)
        val persisted = store.load()
        assertEquals(avant.boxStatus, persisted.boxStatus)
        assertTrue(persisted.transcriptionTasks.isEmpty())
        assertTrue(persisted.automaticProcessingRequests.isEmpty())
        assertTrue(persisted.errors.isEmpty())
        assertEquals(listOf(Cmd.NOISE_REDUCTION), link.written.map { it[3].toInt() and 0xff })
    }

    @Test fun `deux demandes simultanees n emettent qu une commande`() = runBlocking {
        val admission = BoxSettingAdmission()
        val link = FakeLink { cmd, _ ->
            when (cmd) {
                Cmd.NOISE_REDUCTION -> reply(cmd, byteArrayOf(0))
                Cmd.INFO -> reply(Cmd.INFO, statusPayload(noise = 9))
            }
        }
        val session = V05Session(link, {})
        val entered = CountDownLatch(2)
        val decided = CountDownLatch(2)
        val release = CountDownLatch(1)
        suspend fun attempt(): BoxSettingResult? {
            entered.countDown()
            check(entered.await(10, TimeUnit.SECONDS))
            val admitted = admission.tryStart()
            decided.countDown()
            if (!admitted) return null
            try {
                check(release.await(10, TimeUnit.SECONDS))
                return session.writeSetting(BoxSetting.Noise(NoiseReduction.MOYEN))
            } finally {
                admission.finish()
            }
        }
        val first = async(Dispatchers.Default) { attempt() }
        val second = async(Dispatchers.Default) { attempt() }
        assertTrue(decided.await(5, TimeUnit.SECONDS))
        assertTrue(link.written.isEmpty())
        release.countDown()
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it != null })
        assertTrue(results.single { it != null }!!.isApplied(BoxSetting.Noise(NoiseReduction.MOYEN)))
        assertEquals(listOf(Cmd.NOISE_REDUCTION, Cmd.INFO), link.written.map { it[3].toInt() and 0xff })
    }
}
