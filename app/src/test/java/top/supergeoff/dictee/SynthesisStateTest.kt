package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisStateTest {
    @Test fun phasesAndDefaultPromptAreDefined() {
        assertEquals(listOf("WAITING_VTT", "READY", "CALLING", "SAVE_PENDING", "SUSPENDED", "COMPLETED", "CANCELLED"), SynthesisPhase.entries.map { it.name })
        val prompt = SynthesisSettings().prompt
        listOf("Résumé", "Décisions", "Actions", "{{type}}", "{{date}}", "{{duree}}", "{{intervenants}}").forEach { assertTrue(it, prompt.contains(it)) }
        assertTrue(prompt.contains("inventer"))
        assertTrue(prompt.contains("source"))
        assertEquals("/v1", SynthesisSettings().baseUrl)
        assertFalse(SynthesisSettings().model.isNotEmpty())
    }
}
