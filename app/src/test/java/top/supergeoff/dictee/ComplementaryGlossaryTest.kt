package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class ComplementaryGlossaryTest {
    @Test fun preservesEntriesAndNormalizesPastedLineSeparators() {
        assertEquals(listOf(" NeMo ", "Terme (souvent entendu : a, b)"), ComplementaryGlossary.entries(" NeMo \nTerme (souvent entendu : a, b)"))
        assertEquals("NeMo\nA\nB", ComplementaryGlossary.append("NeMo", "A\r\nB\n \n"))
    }
    @Test fun removesOnlySelectedOccurrence() {
        assertEquals("A\nB", ComplementaryGlossary.remove("A\nA\nB", 1))
        assertEquals("A", ComplementaryGlossary.remove("A", 8))
    }
    @Test fun enforcesLineAndCharacterLimitsWithoutRejectingUnicodePairs() {
        assertNull(ComplementaryGlossary.validationError(""))
        assertNull(ComplementaryGlossary.validationError(List(500) { "a" }.joinToString("\n")))
        assertNotNull(ComplementaryGlossary.validationError(List(501) { "a" }.joinToString("\n")))
        assertNull(ComplementaryGlossary.validationError("a".repeat(20000)))
        assertNotNull(ComplementaryGlossary.validationError("a".repeat(20001)))
        assertNull(ComplementaryGlossary.validationError("😀".repeat(20000)))
        assertNotNull(ComplementaryGlossary.validationError("😀".repeat(20001)))
    }
    @Test fun warningsDoNotInvalidateOrRewriteEntries() {
        for (text in listOf("Un deux trois quatre cinq six", "Phrase.", "Phrase!", "Phrase?")) {
            assertTrue(ComplementaryGlossary.hasAdvisory(text))
            assertNull(ComplementaryGlossary.validationError(text))
            assertEquals(text, ComplementaryGlossary.append("", text))
        }
        assertFalse(ComplementaryGlossary.hasAdvisory("NeMo"))
    }
}
