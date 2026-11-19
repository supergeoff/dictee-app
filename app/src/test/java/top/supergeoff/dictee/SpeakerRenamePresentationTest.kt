package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRenamePresentationTest {
    private val labels = listOf("A", "B", "C")

    @Test fun `le message annonce le MD refait ou donne la raison du refus`() {
        assertEquals("VTT mis à jour. Le MD est refait avec ces noms.", speakerRenameMessage(SpeakerRenameResult.Done))
        assertEquals("Aucun nom modifié.", speakerRenameMessage(SpeakerRenameResult.Refused("Aucun nom modifié.")))
    }

    @Test fun `une tache active rend le nommage indisponible avec sa raison`() {
        assertNull(speakerRenameUnavailableReason(taskActive = false))
        assertEquals("Un traitement est en cours sur cet enregistrement.", speakerRenameUnavailableReason(taskActive = true))
    }

    @Test fun `un champ vide laisse l'etiquette et rien n'est enregistrable`() {
        val draft = speakerRenameDraft(labels, emptyMap())
        assertEquals(emptyMap<String, String>(), draft.renames)
        assertTrue(draft.invalidLabels.isEmpty())
        assertTrue(draft.mergeNotices.isEmpty())
        assertFalse(draft.canSave)
        assertFalse(speakerRenameDraft(labels, mapOf("A" to "  ", "B" to "")).canSave)
    }

    @Test fun `les noms saisis sont normalises et gardes par etiquette`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to " Marie ", "B" to "Paul D.", "C" to ""))
        assertEquals(mapOf("A" to "Marie", "B" to "Paul D."), draft.renames)
        assertTrue(draft.canSave)
    }

    @Test fun `saisir l'etiquette elle-meme n'est pas un changement`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to "A", "B" to " B "))
        assertEquals(emptyMap<String, String>(), draft.renames)
        assertFalse(draft.canSave)
    }

    @Test fun `un nom invalide bloque l'enregistrement`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to "Marie", "B" to "a<b"))
        assertEquals(setOf("B"), draft.invalidLabels)
        assertEquals(mapOf("A" to "Marie"), draft.renames)
        assertFalse(draft.canSave)
    }

    @Test fun `deux etiquettes vers le meme nom annoncent une fusion`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to "Marie", "B" to "Marie"))
        assertEquals(listOf("A et B seront fusionnés."), draft.mergeNotices)
        assertTrue(draft.canSave)
    }

    @Test fun `trois etiquettes fusionnees se listent avec une virgule et un et`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to "Marie", "B" to "Marie", "C" to "Marie"))
        assertEquals(listOf("A, B et C seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `un nom egal a une etiquette laissee fusionne avec elle`() {
        val draft = speakerRenameDraft(labels, mapOf("B" to "A"))
        assertEquals(listOf("A et B seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `plusieurs fusions donnent une phrase chacune dans l'ordre des etiquettes`() {
        val draft = speakerRenameDraft(listOf("A", "B", "C", "D"), mapOf("A" to "Marie", "B" to "Marie", "C" to "Paul", "D" to "Paul"))
        assertEquals(listOf("A et B seront fusionnés.", "C et D seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `un echange d'etiquettes n'est pas une fusion`() {
        val draft = speakerRenameDraft(labels, mapOf("A" to "B", "B" to "A"))
        assertTrue(draft.mergeNotices.isEmpty())
        assertEquals(mapOf("A" to "B", "B" to "A"), draft.renames)
    }

    @Test fun `les suggestions filtrent le carnet et taisent le nom deja saisi`() {
        val book = listOf(SpeakerName("Marie", 2), SpeakerName("Paul D.", 1))
        assertEquals(listOf("Marie", "Paul D."), speakerSuggestions(book, "").map { it.name })
        assertEquals(listOf("Marie"), speakerSuggestions(book, " mar ").map { it.name })
        assertEquals(emptyList<SpeakerName>(), speakerSuggestions(book, "Marie"))
        assertEquals(emptyList<SpeakerName>(), speakerSuggestions(book, "zzz"))
    }
}
