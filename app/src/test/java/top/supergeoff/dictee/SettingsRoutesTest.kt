package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test
import top.supergeoff.dictee.ui.SettingsPage

class SettingsRoutesTest {
    @Test fun menuAndDirectLinksResolveToDedicatedSettingsPages() {
        val expected = listOf(SettingsPage.HOME to Screen.Settings, SettingsPage.BOX to Screen.BoxSettings,
            SettingsPage.STORAGE to Screen.StorageSettings, SettingsPage.TRANSCRIPTION to Screen.TranscriptionSettings,
            SettingsPage.SYNTHESIS to Screen.SynthesisSettings)
        for ((page, screen) in expected) {
            assertEquals(screen, SettingsRoutes.screen(page))
            assertEquals(page, SettingsRoutes.page(screen))
        }
        assertEquals(Screen.StorageSettings, SettingsRoutes.screen(SettingsDestination.STORAGE))
        assertEquals(Screen.TranscriptionSettings, SettingsRoutes.screen(SettingsDestination.TRANSCRIPTION))
        assertEquals(Screen.SynthesisSettings, SettingsRoutes.screen(SettingsDestination.SYNTHESIS))
        assertNull(SettingsRoutes.page(Screen.Prompt))
    }

    @Test fun promptAndDriveReturnToTheirParentAndIgnoreStaleCallbacks() {
        for ((parent, child) in listOf(Screen.SynthesisSettings to Screen.Prompt, Screen.StorageSettings to Screen.DriveFolders)) {
            val stack = mutableListOf<Screen>(Screen.Home, Screen.Settings)
            pushScreen(stack, Screen.Settings, parent)
            pushScreen(stack, parent, child)
            pushScreen(stack, parent, child) // callback ancien, aucun doublon
            assertEquals(4, stack.size)
            assertNull(popScreen(stack, parent))
            assertEquals(child, popScreen(stack, child))
            assertEquals(parent, stack.last())
            assertEquals(parent, popScreen(stack, parent))
            assertEquals(Screen.Settings, stack.last())
        }
        val root = mutableListOf<Screen>(Screen.Home)
        assertNull(popScreen(root, Screen.Home))
    }
}
