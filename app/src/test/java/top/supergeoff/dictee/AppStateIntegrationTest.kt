package top.supergeoff.dictee

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AppStateIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "20260101120000"
    private val stream = javaClass.getResource("/stream.bin")!!.readBytes()

    private fun file() = File(tmp.root, "state.json")
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun link() = FakeLink { cmd, _ ->
        if (cmd == Cmd.FILE) {
            reply(Cmd.FILE, byteArrayOf(0) + u32(stream.size.toLong()))
            audio(stream)
            reply(Cmd.FILE, byteArrayOf(2))
        }
    }

    @Test
    fun `la recuperation echouee conserve la protection pendant et apres un echec de sauvegarde`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val store = LocalStateStore(file(), rename = { from, to ->
            if (attempts.incrementAndGet() == 2) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                false
            } else from.renameTo(to)
        })
        val repo = LocalStateRepository(store)
        val safety = RecoverySafety(repo)
        val phone = object : PhoneStore {
            val files = linkedMapOf<String, ByteArray>()
            override fun list() = files.map { PhoneFile(it.key, it.value.size.toLong()) }
            override fun write(fileName: String, source: File) {
                files[fileName] = source.readBytes().copyOf(12)
                throw PhoneStoreException("écriture interrompue")
            }
            override fun open(fileName: String): InputStream = files.getValue(fileName).inputStream()
        }
        val job = RecoveryJob(V05Session(link(), {}), phone, tmp.newFolder(), ListLog())
        val recovery = async(Dispatchers.Default) {
            safety.withTransferLock {
                job.run(listOf(ListItem.Entry(name, stream.size.toLong(), 0)), { _, _ -> },
                    { safety.finish(it) }, { safety.begin(it.name) })
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(12, phone.files.getValue("Call-$name.mp3").size)
            assertTrue(Step.RECOVER in repo.state.value!!.errors.getValue(name))
            val processingStarted = CountDownLatch(1)
            val processing = async(Dispatchers.Default) {
                processingStarted.countDown()
                safety.withTransferLock {
                    val current = repo.awaitInitialized()
                    val recordings = Recordings.merge(emptyList(), phone.list(), null, current.errors, Busy())
                    Recordings.sendable(recordings, current.errors) to
                        Recordings.toTranscribe(recordings, setOf(name), current.errors)
                }
            }
            assertTrue(processingStarted.await(5, TimeUnit.SECONDS))
            kotlinx.coroutines.delay(100)
            assertFalse(processing.isCompleted)
            release.countDown()
            assertTrue(withTimeout(5000) { recovery.await() }.single() is RecoveryResult.Failed)
            val (send, transcription) = withTimeout(5000) { processing.await() }
            assertTrue(send.isEmpty())
            assertTrue(transcription.candidates.isEmpty())
            assertEquals(TranscriptionExclusion.RECOVERY_FAILED, transcription.excluded[name])
            assertTrue(Step.RECOVER in LocalStateStore(file()).load().errors.getValue(name))
        } finally { release.countDown() }
    }

    @Test
    fun `un echec de protection avant recuperation interdit toute ecriture du mp3`() = runBlocking {
        val repo = LocalStateRepository(LocalStateStore(file(), rename = { _, _ -> false }))
        val safety = RecoverySafety(repo)
        val phone = MemoryStore()
        val link = link()
        try {
            safety.withTransferLock {
                RecoveryJob(V05Session(link, {}), phone, tmp.newFolder(), ListLog())
                    .run(listOf(ListItem.Entry(name, stream.size.toLong(), 0)), { _, _ -> },
                        { safety.finish(it) }, { safety.begin(it.name) })
            }
            fail("la protection doit échouer")
        } catch (_: RecoveryGuardException) { }
        assertTrue(phone.files.isEmpty())
        assertTrue(link.sentPayloads(Cmd.FILE).isEmpty())
    }

    @Test
    fun `le modele et un autre consommateur utilisent reellement le meme depot`() = runBlocking {
        val repo = LocalStateRepository(LocalStateStore(file()))
        val modelState = AppLocalState(repo)
        val other = modelState.repository
        assertSame(repo, other)
        val start = CountDownLatch(1)
        val task = sampleTask()
        val first = async(Dispatchers.Default) {
            start.await()
            modelState.setTheme(ThemeChoice.DARK).await().getOrThrow()
        }
        val second = async(Dispatchers.Default) {
            start.await()
            other.updateDurably { it.copy(transcriptionTasks = listOf(task)) }
        }
        start.countDown()
        first.await()
        second.await()
        val state = repo.awaitInitialized()
        assertEquals(ThemeChoice.DARK, state.theme)
        assertEquals(listOf(task), state.transcriptionTasks)
        assertEquals(state, LocalStateStore(file()).load())
    }

    @Test
    fun `la construction ne bloque pas le chargement et les operations attendent l'etat reel`() = runBlocking {
        LocalStateStore(file()).save(LocalState(deviceName = "persisté", theme = ThemeChoice.DARK))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val repoRef = AtomicReference<LocalStateRepository>()
        val thread = Thread {
            repoRef.set(LocalStateRepository(LocalStateStore(file()), load = {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                LocalStateStore(file()).load()
            }))
            returned.countDown()
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            val repo = repoRef.get()
            assertNull(repo.state.value)

            val change = repo.update { it.copy(deviceName = it.deviceName + " modifié") }
            val operation = async(start = CoroutineStart.UNDISPATCHED) { repo.awaitInitialized() }
            assertFalse(change.isCompleted)
            assertFalse(operation.isCompleted)
            release.countDown()
            val loaded = withTimeout(5000) { operation.await() }
            assertTrue(loaded.deviceName == "persisté" || loaded.deviceName == "persisté modifié")
            assertEquals(ThemeChoice.DARK, loaded.theme)
            val changed = withTimeout(5000) { change.await().getOrThrow() }
            assertEquals("persisté modifié", changed.deviceName)
            assertEquals(ThemeChoice.DARK, changed.theme)
            assertEquals(changed, LocalStateStore(file()).load())
        } finally { release.countDown(); thread.join(5000) }
    }
}
