package top.supergeoff.dictee

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.DriveSendDialog

class DriveSendDialogUiTest {
    @get:Rule val compose = createComposeRule()
    private val name = "20261030160502"
    private val recording = Recording(name, RecordType.CALL, 3, false,
        mapOf(FileKind.MP3 to PhoneFile("Call-20261030160502.mp3", 3)), DisplayState.Recovered)

    @Test fun choicesAndMissingDocuments() {
        val confirmations = mutableListOf<Pair<DriveSendChoice, Set<String>>>()
        compose.setContent { DicteeTheme(false) {
            DriveSendDialog(UiState(recordings = listOf(recording)), DriveSendRequest(listOf(name), DriveSendChoice.ALL_FILES),
                { choice, names -> confirmations.add(choice to names) }, {})
        } }
        compose.onNodeWithText("1 enregistrement(s)").assertExists()
        compose.onNodeWithText("Documents manquants : 1 VTT, 1 MD.").assertExists()
        compose.onNodeWithText("MP3 + VTT + MD").performClick()
        compose.onNodeWithText("Envoyer").performClick()
        compose.runOnIdle { assertEquals(listOf(DriveSendChoice.ALL_FILES to setOf(name)), confirmations) }
        compose.onNodeWithText("VTT + MD").performClick()
        compose.onNodeWithText("0 enregistrement(s)").assertExists()
        compose.onNodeWithText("Envoyer").assertIsNotEnabled()
    }

    @Test fun documentsChoiceAndCancel() {
        var confirmed: DriveSendChoice? = null
        var dismissed = 0
        val withVtt = recording.copy(phoneFiles = recording.phoneFiles + (FileKind.VTT to PhoneFile("Call-$name.vtt", 3)))
        compose.setContent { DicteeTheme(false) {
            DriveSendDialog(UiState(recordings = listOf(withVtt)), DriveSendRequest(listOf(name), DriveSendChoice.DOCUMENTS_ONLY),
                { choice, _ -> confirmed = choice }, { dismissed++ })
        } }
        compose.onNodeWithText("Documents manquants : 0 VTT, 1 MD.").assertExists()
        compose.onNodeWithText("Envoyer").performClick()
        compose.runOnIdle { assertEquals(DriveSendChoice.DOCUMENTS_ONLY, confirmed); confirmed = null }
        compose.onNodeWithText("MP3 + VTT + MD").performClick()
        compose.onNodeWithText("Annuler").performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(null, confirmed) }
    }
}
