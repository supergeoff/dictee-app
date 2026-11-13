package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class TranscriptionSettingsPresentationTest {
    @Test fun `les messages ne proposent pas de recréer les tâches terminales`() {
        val tasks = TranscriptionPhase.entries.map { phase ->
            sampleTask().copy(localId = phase.name, recordingName = phase.name, phase = phase,
                serverAddress = "", suspended = true, suspensionReason = INVALID_TASK_ADDRESS_REASON)
        }
        val messages = transcriptionTaskSettingsMessages(tasks)
        val terminal = setOf(TranscriptionPhase.COMPLETED, TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED)
        assertEquals(TranscriptionPhase.entries.size - terminal.size, messages.size)
        tasks.filter { it.phase !in terminal }.forEach { task ->
            assertTrue(messages.any { it.contains(task.recordingName) && it.contains("crée une nouvelle tâche") })
        }
        tasks.filter { it.phase in terminal }.forEach { task ->
            assertFalse(messages.any { it.contains(task.recordingName) })
        }
    }

    @Test fun `les tâches terminales ne reçoivent pas non plus le message de clé inaccessible`() {
        val tasks = TranscriptionPhase.entries.map { phase ->
            sampleTask().copy(recordingName = phase.name, phase = phase,
                suspended = true, suspensionReason = "Clé inaccessible. Vérifie les réglages de transcription.")
        }
        val messages = transcriptionTaskSettingsMessages(tasks)
        assertTrue(messages.any { it.contains("TRACKING") && it.contains("clé inaccessible") })
        listOf("COMPLETED", "FAILED", "CANCELLED").forEach { phase ->
            assertFalse(messages.any { it.contains(phase) })
        }
    }
}
