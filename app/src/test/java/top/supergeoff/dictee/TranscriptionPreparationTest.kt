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
        assertEquals(1, tasks.size)
        val task = tasks.single()
        assertEquals("20261111090000", task.recordingName)
        assertEquals("Note-20261111090000.mp3", task.mp3Name)
        assertEquals("Note-20261111090000.vtt", task.vttName)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", task.mp3Sha256)
        assertEquals("content://folder", task.folderUri)
        assertEquals("https://example.invalid/v1", task.serverAddress)
        assertEquals("key-ref", task.encryptedKeyRef)
        assertEquals(TranscriptionPhase.PENDING, task.phase)
    }

    @Test fun `explicit selection keeps existing vtt name and empty selection produces no task`() {
        val phone = MemoryStore().apply {
            files["Note-20261111090000.mp3"] = "abc".toByteArray()
            files["Call-20261111090000.vtt"] = "old".toByteArray()
        }
        val selected = TranscriptionPreparation.prepare(phone, "content://folder", settings, emptyMap(), setOf("20261111090000")) { "id" }
        assertEquals("Call-20261111090000.vtt", selected.single().vttName)
        assertTrue(TranscriptionPreparation.prepare(phone, "content://folder", settings, emptyMap(), emptySet()) { "id" }.isEmpty())
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
