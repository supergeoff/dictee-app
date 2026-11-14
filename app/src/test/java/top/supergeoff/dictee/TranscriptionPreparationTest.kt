package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class TranscriptionPreparationTest {
    private val settings = TranscriptionSettings("https://example.invalid/v1", "fr", "dictee-diarize", "key-ref")

    @Test fun `preparation freezes folder settings hash and chooses oldest eligible mp3`() {
        val phone = MemoryStore().apply {
            files["Note-20261111090000.mp3"] = "abc".toByteArray()
            files["Note-20261111120000.mp3"] = "abc".toByteArray()
            files["Note-20261111120000.vtt"] = "old".toByteArray()
        }
        val tasks = TranscriptionPreparation.prepare(phone, "content://folder", settings, emptyMap(), null) { "id-" + it }
        assertEquals(1, tasks.size) // Le plan MD seul n'est pas transformé en tâche STT.
        val task = tasks.single()
        assertEquals("20261111090000", task.recordingName)
        assertEquals("Note-20261111090000.mp3", task.mp3Name)
        assertEquals("Note-20261111090000.vtt", task.vttName)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", task.mp3Sha256)
        assertEquals("content://folder", task.folderUri)
        assertEquals("https://example.invalid/v1", task.serverAddress)
        assertEquals("key-ref", task.encryptedKeyRef)
        assertEquals(TranscriptionPhase.PENDING, task.phase)
        assertFalse(task.replaceVtt)
    }

    @Test fun `explicit selection does not replace an existing vtt and empty selection produces no task`() {
        val phone = MemoryStore().apply {
            files["Note-20261111090000.mp3"] = "abc".toByteArray()
            files["Call-20261111090000.vtt"] = "old".toByteArray()
        }
        val selected = TranscriptionPreparation.prepare(phone, "content://folder", settings, emptyMap(), setOf("20261111090000")) { "id" }
        assertTrue(selected.isEmpty())
        assertTrue(TranscriptionPreparation.prepare(phone, "content://folder", settings, emptyMap(), emptySet()) { "id" }.isEmpty())
    }

    @Test fun `md only selection does not create stt task or require stt settings`() {
        val phone = MemoryStore().apply {
            files["Note-20261111090000.mp3"] = "abc".toByteArray()
            files["Note-20261111090000.vtt"] = "old".toByteArray()
        }
        val invalid = settings.copy(baseUrl = "/v1", credentialRef = null)
        val recording = Recordings.merge(emptyList(), phone.list(), null, emptyMap(), Busy()).single()
        val mdPlan = FilePlan(recording, false, true, false, false)
        assertEquals(mdPlan, TranscriptionPreparation.prepare(mdPlan, invalid))
        assertEquals(mdPlan.copy(replaceMd = true), TranscriptionPreparation.prepare(mdPlan.copy(replaceMd = true), invalid))
        assertTrue(TranscriptionPreparation.prepare(phone, "content://folder", invalid, emptyMap(), null) { fail("no STT id"); "id" }.isEmpty())
        try {
            TranscriptionPreparation.prepare(mdPlan.copy(makeVtt = true), invalid)
            fail("missing server accepted for VTT plan")
        } catch (_: InvalidTranscriptionAddressException) {}
        try {
            TranscriptionPreparation.prepare(mdPlan.copy(makeVtt = true), settings.copy(credentialRef = null))
            fail("missing credential accepted for VTT plan")
        } catch (_: CredentialException) {}
    }

    @Test fun `missing credential prevents creation`() {
        val phone = MemoryStore().apply { files["Note-20261111090000.mp3"] = "abc".toByteArray() }
        try {
            TranscriptionPreparation.prepare(phone, "content://folder", settings.copy(credentialRef = null), emptyMap(), null) { "id" }
            fail("missing credential accepted")
        } catch (_: CredentialException) {}
    }

    @Test fun `unsafe server address prevents creation`() {
        val phone = MemoryStore().apply { files["Note-20261111090000.mp3"] = "abc".toByteArray() }
        try {
            TranscriptionPreparation.prepare(phone, "content://folder", settings.copy(baseUrl = "/v1"), emptyMap(), null) { "id" }
            fail("missing server accepted")
        } catch (_: InvalidTranscriptionAddressException) {}
    }
}
