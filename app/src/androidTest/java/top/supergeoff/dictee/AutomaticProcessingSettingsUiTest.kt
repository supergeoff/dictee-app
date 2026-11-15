package top.supergeoff.dictee

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.AutomaticProcessingSetting
import top.supergeoff.dictee.ui.DicteeTheme

class AutomaticProcessingSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlySuccessfulSettingsSaveReexaminesWaitingRequests() = kotlinx.coroutines.runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val file = java.io.File(app.cacheDir, "automatic-wakeup-test.json").apply { delete() }
        val store = LocalStateStore(file)
        val request = AutomaticProcessingRequest("wakeup", "20260101120000", "old-folder", AutomaticProcessingPhase.WAITING_SETTINGS, "initial")
        store.save(LocalState(automaticProcessingRequests = listOf(request)))
        val repository = LocalStateRepository(store)
        val model = AppModel(app, repository)
        kotlinx.coroutines.withTimeout(10000) {
            repository.state.first { it?.automaticProcessingRequests?.singleOrNull()?.reason == "Le dossier du téléphone a changé." }
        }
        repository.updateDurably { it.copy(automaticProcessingRequests = listOf(request.copy(reason = "sentinelle"))) }
        model.transcriptionForm.initialize(TranscriptionSettings())
        model.transcriptionForm.setBaseUrl("https://example.invalid/v1")
        model.setAutoProcessAfterRecovery(true)
        kotlinx.coroutines.withTimeout(10000) { repository.state.first { it?.autoProcessAfterRecovery == true } }
        org.junit.Assert.assertEquals("sentinelle", repository.awaitInitialized().automaticProcessingRequests.single().reason)
        model.transcriptionForm.setBaseUrl("invalid")
        org.junit.Assert.assertFalse(model.transcriptionForm.submit())
        org.junit.Assert.assertEquals("sentinelle", repository.awaitInitialized().automaticProcessingRequests.single().reason)
        model.transcriptionForm.setBaseUrl("https://example.invalid/v1")
        org.junit.Assert.assertTrue(model.transcriptionForm.submit())
        kotlinx.coroutines.withTimeout(10000) {
            repository.state.first { it?.automaticProcessingRequests?.singleOrNull()?.reason == "Le dossier du téléphone a changé." }
        }
        org.junit.Assert.assertTrue(repository.awaitInitialized().transcriptionTasks.isEmpty())
        file.delete()
    }

    @Test fun preferencePersistsInBothThemes() {
        val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "automatic-settings-test.json")
        file.delete()
        val store = LocalStateStore(file)
        var local by mutableStateOf(store.load())
        var dark by mutableStateOf(false)
        compose.setContent {
            DicteeTheme(dark) {
                AutomaticProcessingSetting(local.autoProcessAfterRecovery) { enabled ->
                    store.save(local.copy(autoProcessAfterRecovery = enabled))
                    local = store.load()
                }
            }
        }
        val title = "Créer le VTT et le MD après récupération"
        compose.onNodeWithText(title).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Après chaque récupération, crée les fichiers manquants. L’envoi sur Drive reste manuel.").assertIsDisplayed()
        compose.runOnIdle { local = LocalStateStore(file).load(); dark = true }
        compose.onNodeWithText(title).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { local = LocalStateStore(file).load() }
        compose.onNodeWithText(title).assertIsOff()
        file.delete()
    }
}
