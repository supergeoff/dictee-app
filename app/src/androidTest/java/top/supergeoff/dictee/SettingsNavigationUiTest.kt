package top.supergeoff.dictee

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.*
import top.supergeoff.dictee.ui.*

class SettingsNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsHomeOpensEachDedicatedPageWithoutEmbeddingForms() {
        var selected: SettingsPage? = null
        compose.setContent { DicteeTheme(false) { SettingsHomeSections(ThemeChoice.SYSTEM, {}, {}, { selected = it }) } }
        compose.onNodeWithText("Adresse du serveur (/v1)").assertDoesNotExist()
        for ((title, page) in listOf("Boîtier" to SettingsPage.BOX, "Stockage et Google Drive" to SettingsPage.STORAGE,
            "Transcription" to SettingsPage.TRANSCRIPTION, "Synthèse" to SettingsPage.SYNTHESIS)) {
            compose.onNodeWithText(title).performClick()
            compose.runOnIdle { Assert.assertEquals(page, selected) }
        }
        compose.onNodeWithText("Apparence").assertExists()
        compose.onNodeWithText("Journal").assertExists()
    }
}
