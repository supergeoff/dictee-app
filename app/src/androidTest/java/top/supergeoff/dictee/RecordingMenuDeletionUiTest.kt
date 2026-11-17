package top.supergeoff.dictee

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.RecordingMenu

class RecordingMenuDeletionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuOffersEverywhereAndFreeSpaceInsteadOfOldDeletions() {
        val actions = mutableListOf<String>()
        compose.setContent { DicteeTheme(false) {
            RecordingMenu(false, {}, false, false, {}, {},
                onDeleteEverywhere = { actions += "everywhere" }, onFreeSpace = { actions += "free" })
        } }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Supprimer du téléphone").assertDoesNotExist()
        compose.onNodeWithText("Supprimer du boîtier").assertDoesNotExist()
        compose.onNodeWithText("Supprimer partout").performClick()
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Libérer l'espace").performClick()
        compose.runOnIdle { assertEquals(listOf("everywhere", "free"), actions) }
    }
}
