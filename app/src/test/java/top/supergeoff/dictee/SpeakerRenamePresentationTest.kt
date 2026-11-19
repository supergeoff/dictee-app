package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRenamePresentationTest {
    @Test fun `le message dit ce qui a ete mis a jour`() {
        assertEquals("VTT et MD mis à jour.", speakerRenameMessage(SpeakerRenameResult.Done, "SPEAKER_00", mdPresent = true))
        assertEquals("VTT mis à jour. Le MD ne cite pas SPEAKER_00.", speakerRenameMessage(SpeakerRenameResult.VttOnly, "SPEAKER_00", mdPresent = true))
        assertEquals("VTT mis à jour.", speakerRenameMessage(SpeakerRenameResult.VttOnly, "SPEAKER_00", mdPresent = false))
        assertEquals("Nom invalide.", speakerRenameMessage(SpeakerRenameResult.Refused("Nom invalide."), "SPEAKER_00", mdPresent = true))
    }

    @Test fun `une tache active rend le nommage indisponible avec sa raison`() {
        assertNull(speakerRenameUnavailableReason(taskActive = false))
        assertEquals("Un traitement est en cours sur cet enregistrement.", speakerRenameUnavailableReason(taskActive = true))
    }

    @Test fun `un nom donne a une autre etiquette demande confirmation`() {
        val labels = listOf("SPEAKER_00", "Marie")
        assertEquals("SPEAKER_00 deviendra aussi Marie.", speakerMergeNotice(labels, "SPEAKER_00", "Marie"))
        assertNull(speakerMergeNotice(labels, "SPEAKER_00", "Paul"))
        assertNull(speakerMergeNotice(labels, "Marie", "Marie"))
    }

    @Test fun `les choix filtrent le carnet et proposent d'ajouter un nom nouveau`() {
        val book = listOf(SpeakerName("Marie", 2), SpeakerName("Paul D.", 1))
        val all = speakerNameChoices(book, "")
        assertEquals(listOf("Marie", "Paul D."), all.suggestions.map { it.name })
        assertNull(all.addName)
        val partial = speakerNameChoices(book, " mar ")
        assertEquals(listOf("Marie"), partial.suggestions.map { it.name })
        assertEquals("mar", partial.addName)
        val exact = speakerNameChoices(book, "Marie")
        assertNull(exact.addName)
        assertNull(speakerNameChoices(book, "a>b").addName)
    }
}
