package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRenamePresentationTest {
    private val a = SpeakerLabel("A", "A")
    private val b = SpeakerLabel("B", "B")
    private val c = SpeakerLabel("C", "C")
    private val labels = listOf(a, b, c)

    @Test fun `le message annonce le MD refait ou donne la raison du refus`() {
        assertEquals("VTT mis à jour. Le MD va être refait avec ces noms.", speakerRenameMessage(SpeakerRenameResult.Done))
        assertEquals("Aucun nom modifié.", speakerRenameMessage(SpeakerRenameResult.Refused("Aucun nom modifié.")))
    }

    @Test fun `une tache active rend le nommage indisponible avec sa raison`() {
        assertNull(speakerRenameUnavailableReason(taskActive = false))
        assertEquals("Un traitement est en cours sur cet enregistrement.", speakerRenameUnavailableReason(taskActive = true))
    }

    @Test fun `un champ vide laisse le locuteur et rien n'est enregistrable`() {
        val draft = speakerRenameDraft(labels, emptyMap())
        assertEquals(emptyMap<SpeakerLabel, String>(), draft.renames)
        assertTrue(draft.invalidLabels.isEmpty())
        assertTrue(draft.mergeNotices.isEmpty())
        assertFalse(draft.canSave)
        assertFalse(speakerRenameDraft(labels, mapOf(a to "  ", b to "")).canSave)
    }

    @Test fun `les noms saisis sont normalises et gardes par locuteur`() {
        val draft = speakerRenameDraft(labels, mapOf(a to " Marie ", b to "Paul D.", c to ""))
        assertEquals(mapOf(a to "Marie", b to "Paul D."), draft.renames)
        assertTrue(draft.canSave)
    }

    @Test fun `saisir l'etiquette elle-meme n'est pas un changement`() {
        val draft = speakerRenameDraft(labels, mapOf(a to "A", b to " B "))
        assertEquals(emptyMap<SpeakerLabel, String>(), draft.renames)
        assertFalse(draft.canSave)
    }

    @Test fun `un nom invalide bloque l'enregistrement`() {
        val draft = speakerRenameDraft(labels, mapOf(a to "Marie", b to "a<b"))
        assertEquals(setOf(b), draft.invalidLabels)
        assertEquals(mapOf(a to "Marie"), draft.renames)
        assertFalse(draft.canSave)
    }

    @Test fun `la saisie de depart est le nom courant seulement s'il differe de l'origine`() {
        assertEquals("", a.initialInput)
        assertEquals("Marie", SpeakerLabel("Marie", "A").initialInput)
        assertEquals("", SpeakerLabel("Élodie", null).initialInput)
        assertEquals("A", a.shownOrigin)
        assertEquals("A", SpeakerLabel("Marie", "A").shownOrigin)
        assertEquals("Élodie", SpeakerLabel("Élodie", null).shownOrigin)
    }

    @Test fun `un champ prerempli et inchange n'est pas un changement`() {
        val named = SpeakerLabel("Marie", "A")
        val draft = speakerRenameDraft(listOf(named, b), mapOf(named to named.initialInput))
        assertEquals(emptyMap<SpeakerLabel, String>(), draft.renames)
        assertFalse(draft.canSave)
    }

    @Test fun `vider un locuteur nomme le ramene a son origine`() {
        val named = SpeakerLabel("Marie", "A")
        val draft = speakerRenameDraft(listOf(named, b), mapOf(named to ""))
        assertEquals(mapOf(named to "A"), draft.renames)
        assertTrue(draft.canSave)
        assertEquals(mapOf(named to "A"), speakerRenameDraft(listOf(named), mapOf(named to "  ")).renames)
    }

    @Test fun `saisir l'origine d'un locuteur nomme le ramene a son origine`() {
        val named = SpeakerLabel("Marie", "A")
        assertEquals(mapOf(named to "A"), speakerRenameDraft(listOf(named), mapOf(named to "A")).renames)
    }

    @Test fun `un champ vide sans origine connue laisse le nom courant`() {
        val unknown = SpeakerLabel("Élodie", null)
        val draft = speakerRenameDraft(listOf(unknown), mapOf(unknown to ""))
        assertEquals(emptyMap<SpeakerLabel, String>(), draft.renames)
        assertFalse(draft.canSave)
        assertEquals(mapOf(unknown to "Marie"), speakerRenameDraft(listOf(unknown), mapOf(unknown to "Marie")).renames)
    }

    @Test fun `deux locuteurs vers le meme nom annoncent une fusion`() {
        val draft = speakerRenameDraft(labels, mapOf(a to "Marie", b to "Marie"))
        assertEquals(listOf("A et B seront fusionnés."), draft.mergeNotices)
        assertTrue(draft.canSave)
    }

    @Test fun `trois locuteurs fusionnes se listent avec une virgule et un et`() {
        val draft = speakerRenameDraft(labels, mapOf(a to "Marie", b to "Marie", c to "Marie"))
        assertEquals(listOf("A, B et C seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `un nom egal au nom d'un locuteur laisse tel quel fusionne avec lui`() {
        val draft = speakerRenameDraft(labels, mapOf(b to "A"))
        assertEquals(listOf("A et B seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `plusieurs fusions donnent une phrase chacune dans l'ordre des locuteurs`() {
        val d = SpeakerLabel("D", "D")
        val draft = speakerRenameDraft(listOf(a, b, c, d), mapOf(a to "Marie", b to "Marie", c to "Paul", d to "Paul"))
        assertEquals(listOf("A et B seront fusionnés.", "C et D seront fusionnés."), draft.mergeNotices)
    }

    @Test fun `un echange de noms n'est pas une fusion`() {
        val draft = speakerRenameDraft(labels, mapOf(a to "B", b to "A"))
        assertTrue(draft.mergeNotices.isEmpty())
        assertEquals(mapOf(a to "B", b to "A"), draft.renames)
    }

    @Test fun `une fusion deja enregistree n'est pas annoncee de nouveau`() {
        val first = SpeakerLabel("Marie", "A")
        val second = SpeakerLabel("Marie", "B")
        val draft = speakerRenameDraft(listOf(first, second, c), mapOf(first to "Marie", second to "Marie"))
        assertTrue(draft.mergeNotices.isEmpty())
        assertFalse(draft.canSave)
    }

    @Test fun `vider les deux locuteurs d'une fusion rend les deux origines`() {
        val first = SpeakerLabel("Marie", "A")
        val second = SpeakerLabel("Marie", "B")
        val draft = speakerRenameDraft(listOf(first, second), mapOf(first to "", second to ""))
        assertEquals(mapOf(first to "A", second to "B"), draft.renames)
        assertTrue(draft.mergeNotices.isEmpty())
    }

    @Test fun `vider un seul locuteur d'une fusion ne laisse que l'autre sous le nom`() {
        val first = SpeakerLabel("Marie", "A")
        val second = SpeakerLabel("Marie", "B")
        val draft = speakerRenameDraft(listOf(first, second), mapOf(first to "", second to "Marie"))
        assertEquals(mapOf(first to "A"), draft.renames)
        assertTrue(draft.mergeNotices.isEmpty())
    }

    @Test fun `les suggestions filtrent le carnet et taisent le nom deja saisi`() {
        val book = listOf(SpeakerName("Marie", 2), SpeakerName("Paul D.", 1))
        assertEquals(listOf("Marie", "Paul D."), speakerSuggestions(book, "").map { it.name })
        assertEquals(listOf("Marie"), speakerSuggestions(book, " mar ").map { it.name })
        assertEquals(emptyList<SpeakerName>(), speakerSuggestions(book, "Marie"))
        assertEquals(emptyList<SpeakerName>(), speakerSuggestions(book, "zzz"))
    }
}
