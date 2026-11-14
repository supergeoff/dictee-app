package top.supergeoff.dictee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SynthesisSettingsFormTest {
    @Test fun submitIsSerializedAndClearsKeyOnlyOnSuccess() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<SynthesisSettings, String>>()
        val form = SynthesisSettingsForm(backgroundScope, { settings, secret -> calls += settings to secret; release.await() }, { _, _ -> })
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
        })
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

    @Test fun testingBlocksConcurrentOperationsUntilFinished() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> calls++ }, { _, _ -> calls++; release.await() })
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
        val form = SynthesisSettingsForm(backgroundScope, { _, _ -> calls++; error("synthetic-key") }, { _, _ -> calls++ })
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
