package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class BoxReadingPublisherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val address = "AA"
    private val status = BoxStatus(71, 100, 50, 5, 0, 0)

    @Test fun `reconnexion a la meme adresse ignore une publication retardee`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publisher = BoxReadingPublisher { entered.complete(Unit); release.await(); true }
        val old = publisher.open()
        val pending = async { runCatching { publisher.publish(old, BoxReading(address, status)) } }
        entered.await()
        publisher.close(old)
        val current = publisher.open()
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(publisher.freshAddress.value)
        assertNotEquals(old, current)
    }

    @Test fun `divergence attend la sauvegarde durable`() = runTest {
        val file = File(tmp.root, "state.json")
        val store = LocalStateStore(file)
        store.save(LocalState(deviceAddress = address))
        val repo = LocalStateRepository(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publisher = BoxReadingPublisher { reading ->
            entered.complete(Unit); release.await()
            recordBoxReading(repo, reading).deviceAddress == reading.address
        }
        val generation = publisher.open()
        val messages = mutableListOf<String>()
        val setting = BoxSetting.Noise(NoiseReduction.MOYEN)
        val job = async {
            deliverBoxSetting(setting, {
                val result = BoxSettingResult(true, status)
                publisher.publish(generation, BoxReading(address, result.status))
                result
            }, { messages.add(it) })
        }
        entered.await()
        assertTrue(messages.isEmpty())
        assertNull(publisher.freshAddress.value)
        release.complete(Unit)
        job.await()
        assertEquals(status, store.load().boxStatus)
        assertEquals(address, publisher.freshAddress.value)
        assertEquals(listOf("Réglage non appliqué. La valeur relue est affichée."), messages)
    }

    @Test fun `echec de sauvegarde invalide la fraicheur et ne confirme pas`() = runTest {
        val file = File(tmp.root, "state.json")
        LocalStateStore(file).save(LocalState(deviceAddress = address))
        val goodRepo = LocalStateRepository(LocalStateStore(file))
        val badRepo = LocalStateRepository(LocalStateStore(file, rename = { _, _ -> false }))
        var failSave = false
        val publisher = BoxReadingPublisher { reading ->
            recordBoxReading(if (failSave) badRepo else goodRepo, reading).deviceAddress == reading.address
        }
        val generation = publisher.open()
        val previous = status.copy(noise = 0)
        publisher.publish(generation, BoxReading(address, previous))
        assertEquals(address, publisher.freshAddress.value)
        failSave = true
        val messages = mutableListOf<String>()
        deliverBoxSetting(BoxSetting.Noise(NoiseReduction.FAIBLE), {
            publisher.publish(generation, BoxReading(address, status))
            BoxSettingResult(true, status)
        }, { messages.add(it) })
        assertNull(publisher.freshAddress.value)
        assertTrue(messages.single().contains("non vérifié"))
        assertEquals(previous, LocalStateStore(file).load().boxStatus)
    }
    @Test fun `deux commandes concurrentes attendent une publication sans perdre de lecture`() = runTest {
        val store = LocalStateStore(File(tmp.root, "state.json"))
        store.save(LocalState(deviceAddress = address))
        val repo = LocalStateRepository(store)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publisher = BoxReadingPublisher { reading ->
            entered.complete(Unit)
            release.await()
            recordBoxReading(repo, reading).deviceAddress == reading.address
        }
        val generation = publisher.open()
        val admission = BoxSettingAdmission()
        val link = FakeLink { cmd, _ ->
            when (cmd) {
                Cmd.NOISE_REDUCTION -> reply(cmd, byteArrayOf(0))
                Cmd.INFO -> reply(cmd, ByteArray(BoxStatus.PAYLOAD_LENGTH).also { it[9] = 71; it[39] = 5 })
            }
        }
        val session = V05Session(link, {})
        suspend fun command() {
            if (!admission.tryStart()) return
            try {
                val result = session.writeSetting(BoxSetting.Noise(NoiseReduction.FAIBLE))
                publisher.publish(generation, BoxReading(address, result.status))
            } finally { admission.finish() }
        }
        val first = async { command() }
        entered.await()
        val second = async { command() }
        second.await()
        assertEquals(2, link.written.size)
        release.complete(Unit)
        first.await()
        assertEquals(BoxStatus(71, 0, 0, 5, 0, 0), store.load().boxStatus)
        assertEquals(address, publisher.freshAddress.value)
    }

}
