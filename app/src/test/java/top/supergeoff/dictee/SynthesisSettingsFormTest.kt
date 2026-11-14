package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SynthesisSettingsFormTest {
    @Test fun emptyPromptCannotBeSaved() = runTest {
        var saves = 0
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> saves++ }, { _, _ -> }, { _ -> })
        form.initialize(SynthesisSettings())
        form.setPrompt("   ")
        assertFalse(form.submitPrompt())
        runCurrent()
        assertEquals(0, saves)
        assertEquals("Le prompt ne peut pas être vide.", form.state.value.message)
    }

    @Test fun promptAndServiceActionsAreIndependentAndKeepDrafts() = runTest {
        val services = mutableListOf<Pair<SynthesisSettings, String>>()
        val prompts = mutableListOf<String>()
        val form = SynthesisSettingsForm(backgroundScope, { settings, secret -> services += settings to secret },
            { _, _ -> }, { prompts += it })
        form.initialize(SynthesisSettings(model = "ancien", prompt = "ancien prompt"))
        form.setModel("nouveau")
        form.setSecret("synthetic-key")
        form.setPrompt("nouveau prompt {{type}}")
        form.setBaseUrl("adresse invalide")
        assertTrue(form.submitPrompt())
        runCurrent()
        assertEquals(listOf("nouveau prompt {{type}}"), prompts)
        assertTrue(services.isEmpty())
        assertEquals("synthetic-key", form.state.value.secret)
        assertEquals("nouveau", form.state.value.model)
        form.setBaseUrl("https://example.invalid/v1")
        assertTrue(form.submit())
        runCurrent()
        assertEquals(1, services.size)
        assertEquals("nouveau prompt {{type}}", form.state.value.prompt)
        assertEquals("", form.state.value.secret)
    }

    @Test fun submitIsSerializedAndClearsKeyOnlyOnSuccess() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<SynthesisSettings, String>>()
        val form = SynthesisSettingsForm(backgroundScope, { settings, secret -> calls += settings to secret; release.await() }, { _, _ -> }, { _ -> })
        form.initialize(SynthesisSettings())
        form.setBaseUrl("https://example.invalid/v1")
        form.setModel("model")
        form.setSecret("synthetic-key")
        assertTrue(form.submit())
        assertFalse(form.submit())
        assertFalse(form.test())
        runCurrent()
        form.setSecret("other")
        assertEquals("synthetic-key", form.state.value.secret)
        release.complete(Unit)
        runCurrent()
        assertEquals("", form.state.value.secret)
        assertFalse(form.state.value.saving)
        assertEquals("model", calls.single().first.model)
        assertEquals("synthetic-key", calls.single().second)
        assertTrue(form.state.value.message!!.contains("enregistrés"))
    }

    @Test fun testUsesDraftDoesNotSaveAndReportsSuccessAndFailure() = runTest {
        var saves = 0
        var failTest = false
        val calls = mutableListOf<Pair<SynthesisSettings, String>>()
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> saves++ }, { settings, secret ->
            calls += settings to secret
            if (failTest) error("synthetic-key")
        }, { _ -> })
        form.initialize(SynthesisSettings(baseUrl = "https://example.invalid/v1", model = "old", credentialRef = "saved-ref"))
        form.setBaseUrl("https://new.example.invalid/v1")
        form.setModel("new-model")
        form.setPrompt("nouveau prompt")
        form.setSecret("synthetic-key")
        assertTrue(form.test())
        runCurrent()
        assertEquals("https://new.example.invalid/v1", calls.single().first.baseUrl)
        assertEquals("new-model", calls.single().first.model)
        assertEquals("nouveau prompt", calls.single().first.prompt)
        assertEquals("synthetic-key", calls.single().second)
        assertEquals(0, saves)
        assertEquals("synthetic-key", form.state.value.secret)
        assertTrue(form.state.value.message!!.contains("réussi"))
        failTest = true
        form.setSecret("")
        assertTrue(form.test())
        runCurrent()
        assertEquals("", calls.last().second)
        assertTrue(form.state.value.message!!.contains("échoué"))
        assertFalse(form.state.value.message!!.contains("synthetic-key"))
        assertFalse(form.state.value.toString().contains("synthetic-key"))
    }

    @Test fun testExposesOnlyCategorizedSafeFailures() = runTest {
        var failure: Exception = SynthesisClientException(SynthesisFailureKind.TRANSIENT)
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> throw failure }, { _, _ -> throw failure }, { _ -> })
        form.initialize(SynthesisSettings())
        form.setSecret("synthetic-key")
        for (kind in SynthesisFailureKind.entries) {
            failure = SynthesisClientException(kind)
            assertTrue(form.test())
            runCurrent()
            assertEquals("Test de synthèse échoué. ${failure.message}", form.state.value.message)
            assertEquals("synthetic-key", form.state.value.secret)
        }
        failure = IllegalStateException("synthetic-key fournisseur")
        assertTrue(form.test())
        runCurrent()
        assertEquals("Test de synthèse échoué. Vérifie les réglages.", form.state.value.message)
        failure = SynthesisClientException(SynthesisFailureKind.TRANSIENT)
        assertTrue(form.submit())
        runCurrent()
        assertEquals("Impossible d’enregistrer les réglages de synthèse. Réessaie.", form.state.value.message)
    }

    @Test fun unresponsiveTestTimesOutCancelsAndUnlocksForm() = runTest {
        var cancelled = false
        var saves = 0
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> saves++ }, { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }, { _ -> })
        form.initialize(SynthesisSettings(baseUrl = "https://example.invalid/v1", model = "model"))
        form.setSecret("synthetic-key")
        assertTrue(form.test())
        runCurrent()
        assertTrue(form.state.value.testing)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(form.state.value.testing)
        assertEquals("Test de synthèse échoué. La connexion au service de synthèse a échoué ou a expiré.", form.state.value.message)
        assertEquals("synthetic-key", form.state.value.secret)
        form.setModel("nouveau")
        assertEquals("nouveau", form.state.value.model)
        assertTrue(form.submit())
        runCurrent()
        assertEquals(1, saves)
    }

    @Test fun testingBlocksConcurrentOperationsUntilFinished() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> calls++ }, { _, _ -> calls++; release.await() }, { _ -> })
        form.initialize(SynthesisSettings())
        form.setSecret("synthetic-key")
        assertTrue(form.test())
        assertTrue(form.state.value.testing)
        assertFalse(form.test())
        assertFalse(form.submit())
        form.setModel("ignored")
        runCurrent()
        assertEquals(1, calls)
        assertEquals("", form.state.value.model)
        release.complete(Unit)
        runCurrent()
        assertFalse(form.state.value.testing)
        assertEquals("synthetic-key", form.state.value.secret)
    }

    @Test fun invalidAddressDoesNotCallSaveOrTestAndFailureKeepsKey() = runTest {
        var calls = 0
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> calls++; error("synthetic-key") }, { _, _ -> calls++ }, { _ -> })
        form.initialize(SynthesisSettings())
        form.setSecret("synthetic-key")
        form.setBaseUrl("https://user:synthetic-key@example.invalid/v1")
        assertFalse(form.submit())
        assertFalse(form.test())
        assertEquals(0, calls)
        assertFalse(form.state.value.message!!.contains("synthetic-key"))
        form.setBaseUrl("/v1")
        assertTrue(form.submit())
        runCurrent()
        assertEquals("synthetic-key", form.state.value.secret)
        assertFalse(form.state.value.message!!.contains("synthetic-key"))
        assertFalse(form.state.value.toString().contains("synthetic-key"))
    }
}
