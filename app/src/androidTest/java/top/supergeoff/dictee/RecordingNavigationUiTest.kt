package top.supergeoff.dictee

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.RecordingScreen

class RecordingNavigationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailReturnsWhenRecordingDisappears() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val file = java.io.File(app.cacheDir, "recording-navigation-test.json").apply { delete() }
        val model = AppModel(app, LocalStateRepository(LocalStateStore(file)))
        val name = "20261030160502"
        val recording = Recording(name, RecordType.CALL, 0, true, emptyMap(), DisplayState.OnBox)
        var ui by mutableStateOf(UiState(recordings = listOf(recording)))
        var backCount = 0
        try {
            compose.setContent { DicteeTheme(false) { RecordingScreen(ui, name, model, { backCount++ }, {}) } }
            compose.runOnIdle { assertEquals(0, backCount); ui = ui.copy(recordings = emptyList()) }
            compose.runOnIdle { assertEquals(1, backCount) }
        } finally {
            file.delete()
        }
    }
}
