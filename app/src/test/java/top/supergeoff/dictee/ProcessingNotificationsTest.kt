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
        assertEquals(ProcessingResultNotice("id", "Traitement terminé", "Enregistrement — VTT et MD créés."), processingResultNotice(waiting, completed))
        assertNull(processingResultNotice(completed, completed))
    }

    @Test fun `failure and suspension report their reason but cancellation does not`() {
        val failed = task.copy(phase = TranscriptionPhase.FAILED, error = "VTT invalide.")
        assertTrue(processingResultNotice(task, failed)!!.text.endsWith("VTT invalide."))
        val suspended = task.copy(suspended = true, suspensionReason = "Dossier inaccessible.")
        assertTrue(processingResultNotice(task, suspended)!!.text.endsWith("Dossier inaccessible."))
        assertEquals("Traitement interrompu", processingResultNotice(task, suspended)?.title)
        assertNull(processingResultNotice(suspended, suspended))
        assertNull(processingResultNotice(task, task.copy(phase = TranscriptionPhase.CANCELLED)))
        assertNull(processingResultNotice(task.copy(phase = TranscriptionPhase.CANCEL_REQUESTED), failed))
    }

    @Test fun `batch notices identify recordings without exposing sensitive contents`() {
        val first = task.copy(recordingName = "20261111120000", mp3Name = "Note-20261111120000.mp3",
            encryptedKeyRef = "clé secrète", synthesis = work.copy(context = SynthesisContext("url", "clé secrète", "model", "prompt secret",
                PromptMetadata("type", "date", "durée"), "prompt secret", "hash")))
        val second = first.copy(localId = "second", recordingName = "20261111120030", mp3Name = "Note-20261111120030.mp3")
        val third = first.copy(localId = "third", recordingName = "20271111120000", mp3Name = "Note-20271111120000.mp3")
        val notices = listOf(first, second, third).map { recording ->
            val completed = recording.copy(phase = TranscriptionPhase.COMPLETED,
                synthesis = recording.synthesis!!.copy(phase = SynthesisPhase.COMPLETED))
            processingResultNotice(recording, completed)!!
        }
        assertNotEquals(notices[0].text, notices[1].text)
        assertNotEquals(notices[0].text, notices[2].text)
        assertTrue(notices[0].text.contains("Note · 11 nov. 2026 12:00:00"))
        assertTrue(notices[1].text.contains("Note · 11 nov. 2026 12:00:30"))
        assertTrue(notices[2].text.contains("Note · 11 nov. 2027 12:00:00"))
        for (notice in notices) {
            assertEquals("Traitement terminé", notice.title)
            assertTrue(notice.text.contains("VTT et MD créés."))
            for (secret in listOf("clé secrète", "prompt secret", "synthèse secrétique", "contenu secret de réunion")) {
                assertFalse(notice.title.contains(secret))
                assertFalse(notice.text.contains(secret))
            }
        }
    }

    @Test fun `invalid recording names use a generic safe label`() {
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
        assertTrue(processingResultNotice(privateTask, privateTask.copy(phase = TranscriptionPhase.FAILED))!!.text.endsWith("Le traitement a échoué."))
    }

    @Test fun `missing synthesis configuration is a suspension not a global success`() {
        val next = task.copy(phase = TranscriptionPhase.COMPLETED, synthesis = work.copy(phase = SynthesisPhase.SUSPENDED, suspensionReason = "Configure la synthèse."))
        assertTrue(processingResultNotice(task, next)!!.text.endsWith("Configure la synthèse."))
    }
}
