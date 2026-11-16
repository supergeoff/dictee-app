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

    @Test fun onlySuccessfulSettingsSaveReexaminesWaitingRequests(): Unit = kotlinx.coroutines.runBlocking {
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

    @Test fun successfulSaveSchedulesEligibleRequestFromSyntheticFolder() = kotlinx.coroutines.runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val file = java.io.File(app.cacheDir, "automatic-eligible-test.json").apply { delete() }
        val context = InstrumentationRegistry.getInstrumentation().context
        val resolver = android.test.mock.MockContentResolver(context)
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getContentResolver(): android.content.ContentResolver = resolver
        }
        val provider = FakeDocumentsProvider()
        provider.attachInfo(wrapped, android.content.pm.ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        resolver.addProvider(FakeDocumentsProvider.AUTHORITY, provider)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT).toString()
        val name = "20260101120000"
        provider.put("Note-$name.mp3", "synthetic audio")
        val request = AutomaticProcessingRequest("eligible", name, tree, AutomaticProcessingPhase.WAITING_SETTINGS, "initial")
        val store = LocalStateStore(file)
        store.save(LocalState(phoneTreeUri = tree, automaticProcessingRequests = listOf(request)))
        val repository = LocalStateRepository(store)
        val scheduled = java.util.concurrent.CopyOnWriteArrayList<String>()
        val credentials = object : CredentialStore {
            override fun save(secret: String) = "synthetic"
            override fun read(ref: String) = "synthetic"
            override fun remove(ref: String) = Unit
        }
        val client = object : TranscriptionClient {
            override suspend fun submit(task: TranscriptionTask): SubmitResult = error("network forbidden")
            override suspend fun get(task: TranscriptionTask): ServerTask = error("network forbidden")
            override suspend fun cancel(task: TranscriptionTask): ServerTask = error("network forbidden")
            override suspend fun test(settings: TranscriptionSettings) = error("network forbidden")
        }
        val model = AppModel(app, repository, transcriptionCoordinatorFactory = { repo ->
            TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
                verifyAudio = { true }, schedule = { scheduled.add(it) }, prepareAutomatic = { recordingName, folder ->
                    val state = repo.awaitInitialized()
                    TranscriptionPreparation.prepareAutomatic(PhoneFolder(wrapped, android.net.Uri.parse(folder)), folder,
                        state.transcriptionSettings, state.errors, recordingName, state.synthesisSettings) { "generated" }
                })
        })
        try {
            kotlinx.coroutines.withTimeout(10000) {
                repository.state.first { it != null && it.automaticProcessingRequests.singleOrNull()?.reason?.let { reason -> reason != "initial" } == true }
            }
            model.transcriptionForm.initialize(TranscriptionSettings())
            model.transcriptionForm.setBaseUrl("https://example.invalid/v1")
            model.transcriptionForm.setSecret("synthetic-key")
            org.junit.Assert.assertTrue(repository.awaitInitialized().transcriptionTasks.isEmpty())
            org.junit.Assert.assertTrue(model.transcriptionForm.submit())
            kotlinx.coroutines.withTimeout(10000) {
                repository.state.first { it?.transcriptionTasks?.any { task -> task.localId == request.id } == true }
                while (!scheduled.contains(request.id)) kotlinx.coroutines.delay(10)
            }
            val result = repository.awaitInitialized()
            org.junit.Assert.assertTrue(result.automaticProcessingRequests.isEmpty())
            org.junit.Assert.assertEquals(name, result.transcriptionTasks.single().recordingName)
            org.junit.Assert.assertEquals(tree, result.transcriptionTasks.single().folderUri)
        } finally {
            provider.reset()
            file.delete()
        }
    }

    @Test fun listButtonsAndWaitingDetailActionRemainAvailable() {
        val calls = mutableListOf<String>()
        val name = "20260101120000"
        val recording = Recording(name, RecordType.NOTE, 10, false,
            mapOf(FileKind.MP3 to PhoneFile("Note-$name.mp3", 10)), DisplayState.OnBox)
        val request = AutomaticProcessingRequest("id", name, "folder", AutomaticProcessingPhase.WAITING_SETTINGS, "Clé à configurer")
        val input = PipelineInput(recording, LocalState(phoneTreeUri = "folder", automaticProcessingRequests = listOf(request)),
            Busy(), null, false, false)
        val presentation = RecordingPipeline.calculate(input)
        compose.setContent {
            DicteeTheme(false) {
                androidx.compose.foundation.layout.Column {
                    top.supergeoff.dictee.ui.ActionBarButtons("Récupérer (1)", true, 1, "Envoyer (1)", true, true,
                        { calls.add("recover") }, { calls.add("transcribe") }, { calls.add("send") })
                    top.supergeoff.dictee.ui.PipelineCard(presentation) { action ->
                        calls.add("$action:${presentation.settingsDestination(input)}")
                    }
                }
            }
        }
        compose.onNodeWithText("Récupérer (1)").performClick()
        compose.onNodeWithText("VTT + MD (1)").performClick()
        compose.onNodeWithText("Envoyer (1)").performClick()
        compose.onNodeWithText("Ouvrir les réglages").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(listOf("recover", "transcribe", "send", "OPEN_SETTINGS:TRANSCRIPTION"), calls)
        }
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
