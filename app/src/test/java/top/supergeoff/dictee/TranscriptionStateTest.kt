package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionStateTest {
    @Test fun `les phases et la suspension sont representables`() {
        assertEquals(listOf("PENDING", "SUBMITTING", "UNKNOWN_SUBMISSION", "TRACKING", "CANCEL_REQUESTED", "SAVING", "COMPLETED", "FAILED", "CANCELLED"), TranscriptionPhase.entries.map { it.name })
        val task = sampleTask().copy(suspended = true, suspensionReason = "connexion absente")
        assertEquals("connexion absente", task.suspensionReason)
    }
}

internal fun sampleTask() = TranscriptionTask(
    localId = "local-1", recordingName = "20261030160502", mp3Name = "Note-20261030160502.mp3",
    vttName = "Note-20261030160502.vtt", folderUri = "content://test/tree", mp3Sha256 = "a".repeat(64),
    serverAddress = "https://example.invalid", encryptedKeyRef = "encrypted-key-ref", language = "fr", model = "test-model",
    phase = TranscriptionPhase.PENDING, serverId = null, progress = null, error = null,
    suspended = false, suspensionReason = null,
)
