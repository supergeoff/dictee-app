package top.supergeoff.dictee

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.*
import org.junit.*
import top.supergeoff.dictee.ui.*

class GlossaryEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() = scope.cancel()

    @Test fun validationCreatesBubblesAndCrossRemovesOnlyItsOccurrence() {
        val form = TranscriptionSettingsForm(scope) { _, _ -> }
        form.initialize(TranscriptionSettings(glossary = "A\nA"))
        compose.setContent { DicteeTheme(false) { Column(Modifier.verticalScroll(rememberScrollState())) { GlossaryEditor(form) } } }
        compose.onNodeWithTag("glossary-input").performScrollTo().performTextInput("NeMo")
        compose.onNodeWithTag("glossary-input").performImeAction()
        compose.onNodeWithTag("glossary-entry-2").performScrollTo().assertTextContains("NeMo")
        compose.onNodeWithTag("glossary-remove-1").performClick()
        compose.runOnIdle { Assert.assertEquals("A\nNeMo", form.state.value.glossary) }
    }

    @Test fun pastedLinesBecomeBubblesAndOversizedInputIsRetained() {
        val form = TranscriptionSettingsForm(scope) { _, _ -> }
        form.initialize(TranscriptionSettings())
        compose.setContent { DicteeTheme(true) { Column(Modifier.verticalScroll(rememberScrollState())) { GlossaryEditor(form) } } }
        compose.onNodeWithTag("glossary-input").performTextInput("NeMo\nTerme (souvent entendu : a, b)")
        compose.onNodeWithText("Ajouter").performScrollTo().performClick()
        compose.onNodeWithTag("glossary-entry-1").performScrollTo().assertTextContains("Terme (souvent entendu : a, b)")
        compose.runOnIdle { form.setGlossaryInput("a".repeat(20001)) }
        compose.onNodeWithText("Ajouter").performScrollTo().performClick()
        compose.runOnIdle {
            Assert.assertEquals(20001, form.state.value.glossaryInput.length)
            Assert.assertEquals("NeMo\nTerme (souvent entendu : a, b)", form.state.value.glossary)
            Assert.assertNotNull(form.state.value.message)
        }
    }
}
