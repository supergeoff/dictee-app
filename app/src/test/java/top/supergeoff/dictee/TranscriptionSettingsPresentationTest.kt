package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class TranscriptionSettingsPresentationTest {
    @Test fun `le motif de contexte incomplet est visible avant abandon et son avertissement persiste après`() {
        val blocked = sampleTask().copy(model = "", suspended = true, suspensionReason = INVALID_TASK_CONTEXT_REASON)
        val message = transcriptionTaskSettingsMessages(listOf(blocked)).single()
        assertTrue(message.contains(blocked.recordingName))
        assertTrue(message.contains(INVALID_TASK_CONTEXT_REASON))
        val abandoned = blocked.copy(phase = TranscriptionPhase.CANCELLED, suspended = false, suspensionReason = LOCAL_ABANDON_WARNING)
        val warning = LOCAL_ABANDON_WARNING
        assertTrue(transcriptionTaskSettingsMessages(listOf(abandoned)).single().contains(warning))
        assertTrue(transcriptionTaskSettingsMessages(listOf(abandoned.copy(suspensionReason = null))).isEmpty())
    }

    @Test fun `une suspension ancienne garde son motif et indique aussi le contexte incomplet`() {
        val blocked = sampleTask().copy(language = "", suspended = true, suspensionReason = "Connexion absente")
        val message = transcriptionTaskSettingsMessages(listOf(blocked)).single()
        assertTrue(message.contains("Connexion absente"))
        assertTrue(message.contains("Contexte de transcription incomplet"))
        val addressBlocked = blocked.copy(serverAddress = "", suspensionReason = INVALID_TASK_ADDRESS_REASON)
        val addressMessage = transcriptionTaskSettingsMessages(listOf(addressBlocked)).single()
        assertTrue(addressMessage.contains("adresse de serveur invalide"))
        assertTrue(addressMessage.contains("Contexte de transcription incomplet"))
        val withoutReason = blocked.copy(suspensionReason = null)
        assertTrue(transcriptionTaskSettingsMessages(listOf(withoutReason)).single().contains("Contexte de transcription incomplet"))
    }

    @Test fun `le dialogue d'annulation précise l'abandon local pour un contexte incomplet`() {
        val blocked = sampleTask().copy(model = "", suspended = true, suspensionReason = INVALID_TASK_CONTEXT_REASON)
        val explanation = transcriptionCancellationMessage(blocked)
        assertTrue(explanation.contains("local"))
        assertTrue(explanation.contains("pas confirmé"))
        assertTrue(transcriptionCancellationMessage(sampleTask()).contains("autres clients"))
        assertTrue(transcriptionCancellationMessage(null).contains("autres clients"))
        assertTrue(transcriptionCancellationMessage(blocked.copy(suspended = false)).contains("autres clients"))
        val synthesis = transcriptionCancellationMessage(blocked.copy(phase = TranscriptionPhase.SYNTHESIZING))
        assertTrue(synthesis.contains("facturation"))
        assertTrue(synthesis.contains("arrêt"))
    }

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
