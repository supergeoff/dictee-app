package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class BoxDeletionPresentationTest {
    @Test fun confirmationCountsAndWarning() {
        assertEquals("Supprimer 1 enregistrement du boîtier.", boxDeletionCountText(1))
        assertEquals("Supprimer 2 enregistrements du boîtier.", boxDeletionCountText(2))
        assertEquals("Attention : aucune autre copie audio n'est confirmée pour 1 enregistrement.", boxDeletionWarningText(1))
        assertNull(boxDeletionWarningText(0))
    }

    @Test fun anyBlockedTargetDisablesWholeSelection() {
        assertFalse(boxDeletionCanConfirm(BoxDeletionPlan("box", listOf("a", "b"), setOf("b"), 1), false))
        assertTrue(boxDeletionCanConfirm(BoxDeletionPlan("box", listOf("a", "b"), emptySet(), 1), false))
        assertFalse(boxDeletionCanConfirm(BoxDeletionPlan("box", listOf("a"), emptySet(), 0), true))
    }

    @Test fun selectionRetainsUnconfirmedAndOtherPresentRows() {
        fun recording(name: String, onBox: Boolean) = Recording(name, RecordType.NOTE, 1, onBox,
            emptyMap(), DisplayState.Recovered)
        val remaining = selectionAfterBoxDeletion(setOf("confirmed", "refused", "other", "gone"),
            setOf("confirmed", "refused", "gone"),
            listOf(recording("confirmed", false), recording("refused", true), recording("other", false)))
        assertEquals(setOf("refused", "other"), remaining)
    }

    @Test fun partialResultDistinguishesUnverified() {
        val text = boxDeletionResultText(BoxDeletionResult(setOf("a"), mapOf("b" to 1), setOf("c"), setOf("d"), "Coupure"))
        assertTrue(text.contains("Confirmés : a"))
        assertTrue(text.contains("Refusés : b"))
        assertTrue(text.contains("Non vérifiés : c"))
        assertTrue(text.contains("Non traités : d"))
        assertTrue(text.contains("Coupure"))
        assertFalse(text.contains("c supprimé"))
    }
}
