package top.supergeoff.dictee

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import top.supergeoff.dictee.ui.RecordingMenu
import top.supergeoff.dictee.ui.SelectionDeletionButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.PhoneDeletionDialog

class PhoneDeletionDialogUiTest {
    @get:Rule val compose = createComposeRule()
    private val name = "20261030160502"
    private val recording = Recording(name, RecordType.CALL, 3, false,
        mapOf(FileKind.MP3 to PhoneFile("Call-$name.mp3", 3)), DisplayState.Recovered)

    @Test fun detailMenuRequestsDeletion() {
        var requests = 0
        compose.setContent { DicteeTheme(false) {
            RecordingMenu(false, {}, false, false, {}, {}, onDelete = { requests++ })
        } }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Supprimer du téléphone").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun selectionRequestsDeletion() {
        var requests = 0
        compose.setContent { DicteeTheme(false) { SelectionDeletionButton { requests++ } } }
        compose.onNodeWithText("Supprimer du téléphone").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun warningsAndCancellation() {
        var cancelled = 0
        var confirmed = 0
        val plan = PhoneDeletionPlan("folder", listOf(recording), emptySet(), true, true)
        compose.setContent { DicteeTheme(false) {
            PhoneDeletionDialog(plan, false, { confirmed++ }, { cancelled++ })
        } }
        compose.onNodeWithText("Supprimer du téléphone ?").assertExists()
        compose.onNodeWithText("1 enregistrement(s)").assertExists()
        compose.onNodeWithText("La sauvegarde des documents VTT ou MD sur Drive ne peut pas être vérifiée.").assertExists()
        compose.onNodeWithText("L'audio risque d'être perdu : aucune autre copie n'est connue.").assertExists()
        compose.onNodeWithText("Annuler").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, confirmed) }
    }

    @Test fun confirmationUsesDisplayedTargetCount() {
        var confirmed = 0
        val second = recording.copy(name = "20261030160503")
        val plan = PhoneDeletionPlan("folder", listOf(recording, second), emptySet(), false, false)
        compose.setContent { DicteeTheme(false) {
            PhoneDeletionDialog(plan, false, { confirmed++ }, {})
        } }
        compose.onNodeWithText("2 enregistrement(s)").assertExists()
        compose.onNodeWithText("Supprimer").performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun blockedRequestsCannotBeConfirmed() {
        val plan = PhoneDeletionPlan("folder", listOf(recording), setOf(name), false, false)
        compose.setContent { DicteeTheme(false) {
            PhoneDeletionDialog(plan, false, {}, {})
        } }
        compose.onNodeWithText("Certains enregistrements sont occupés : $name. Arrête leur traitement ou retire-les de la file avant de les supprimer.").assertExists()
        compose.onNodeWithText("Supprimer").assertIsNotEnabled()
    }

    @Test fun executionDisablesBothActions() {
        val plan = PhoneDeletionPlan("folder", listOf(recording), emptySet(), false, false)
        compose.setContent { DicteeTheme(false) {
            PhoneDeletionDialog(plan, true, {}, {})
        } }
        compose.onNodeWithText("Supprimer").assertIsNotEnabled()
        compose.onNodeWithText("Annuler").assertIsNotEnabled()
    }
}
