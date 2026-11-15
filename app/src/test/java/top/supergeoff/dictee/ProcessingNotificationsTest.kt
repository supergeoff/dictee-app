package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class ProcessingNotificationsTest {
    private val task = TranscriptionTask("id", "réunion", "a.mp3", "a.vtt", "folder", "hash", "url", "key", "fr", "model", TranscriptionPhase.SAVING)
    private val work = SynthesisWork(SynthesisPhase.WAITING_VTT, "a.md", false, null, 0, null, null, null)

    @Test fun `only both saved files produce success`() {
        val waiting = task.copy(phase = TranscriptionPhase.SYNTHESIZING, synthesis = work)
        assertNull(processingResultNotice(task, waiting))
        assertNull(processingResultNotice(task, task.copy(phase = TranscriptionPhase.COMPLETED)))
        val completed = waiting.copy(phase = TranscriptionPhase.COMPLETED, synthesis = work.copy(phase = SynthesisPhase.COMPLETED))
        assertEquals(ProcessingResultNotice("id", "Traitement terminé", "VTT et MD créés."), processingResultNotice(waiting, completed))
        assertNull(processingResultNotice(completed, completed))
    }

    @Test fun `failure and suspension report their reason but cancellation does not`() {
        val failed = task.copy(phase = TranscriptionPhase.FAILED, error = "VTT invalide.")
        assertEquals("VTT invalide.", processingResultNotice(task, failed)?.text)
        val suspended = task.copy(suspended = true, suspensionReason = "Dossier inaccessible.")
        assertEquals("Dossier inaccessible.", processingResultNotice(task, suspended)?.text)
        assertEquals("Traitement interrompu", processingResultNotice(task, suspended)?.title)
        assertNull(processingResultNotice(suspended, suspended))
        assertNull(processingResultNotice(task, task.copy(phase = TranscriptionPhase.CANCELLED)))
        assertNull(processingResultNotice(task.copy(phase = TranscriptionPhase.CANCEL_REQUESTED), failed))
    }

    @Test fun `notices exclude task credentials prompts and meeting contents`() {
        val privateTask = task.copy(recordingName = "contenu secret de réunion", encryptedKeyRef = "clé secrète",
            synthesis = work.copy(context = SynthesisContext("url", "clé secrète", "model", "prompt secret",
                PromptMetadata("type", "date", "durée"), "prompt secret", "hash")))
        val completed = privateTask.copy(phase = TranscriptionPhase.COMPLETED,
            synthesis = privateTask.synthesis!!.copy(phase = SynthesisPhase.COMPLETED))
        val notice = processingResultNotice(privateTask, completed)!!
        for (secret in listOf("contenu secret de réunion", "clé secrète", "prompt secret")) {
            assertFalse(notice.title.contains(secret))
            assertFalse(notice.text.contains(secret))
        }
        assertEquals("Le traitement a échoué.", processingResultNotice(privateTask, privateTask.copy(phase = TranscriptionPhase.FAILED))?.text)
    }

    @Test fun `missing synthesis configuration is a suspension not a global success`() {
        val next = task.copy(phase = TranscriptionPhase.COMPLETED, synthesis = work.copy(phase = SynthesisPhase.SUSPENDED, suspensionReason = "Configure la synthèse."))
        assertEquals("Configure la synthèse.", processingResultNotice(task, next)?.text)
    }
}
