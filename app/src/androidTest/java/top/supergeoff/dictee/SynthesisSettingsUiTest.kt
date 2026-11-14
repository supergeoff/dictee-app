package top.supergeoff.dictee

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.PromptScreen
import top.supergeoff.dictee.ui.SynthesisSection

class SynthesisSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() = scope.cancel()

    @Test fun settingsEditSaveAndTestWithoutDisplayingStoredKey() {
        val saved = mutableListOf<Pair<SynthesisSettings, String>>()
        var fail = false
        val form = SynthesisSettingsForm(scope, { settings, secret -> saved += settings to secret }, { _, _ ->
            if (fail) throw SynthesisClientException(SynthesisFailureKind.PERMANENT)
        }, { _ -> })
        val settings = SynthesisSettings("https://example.invalid/v1", "model", "saved-ref")
        compose.setContent { DicteeTheme(false) { Column(Modifier.verticalScroll(rememberScrollState())) { SynthesisSection(settings, form, {}) } } }
        compose.onNodeWithTag("synthesis-address").performTextClearance()
        compose.onNodeWithTag("synthesis-address").performTextInput("https://new.example.invalid/v1")
        compose.onNodeWithTag("synthesis-model").performScrollTo().performTextClearance()
        compose.onNodeWithTag("synthesis-model").performTextInput("new-model")
        compose.onNodeWithTag("synthesis-secret").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("synthesis-secret").performScrollTo().performTextInput("synthetic-key")
        compose.onNodeWithText("Tester").performScrollTo().performClick()
        compose.onNodeWithText("Test de synthèse réussi.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("« Tester » envoie une courte requête au modèle et peut entraîner un coût. Ce test ne vérifie pas qu’un VTT de deux heures tient dans son contexte.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { fail = true }
        compose.onNodeWithText("Tester").performScrollTo().performClick()
        compose.onNodeWithText("Test de synthèse échoué. La requête de synthèse a été refusée ou sa configuration est invalide.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("synthesis-secret").performScrollTo().performTextClearance()
        compose.onNodeWithTag("synthesis-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("", saved.single().second)
            assertEquals("https://new.example.invalid/v1", saved.single().first.baseUrl)
            assertEquals("new-model", saved.single().first.model)
        }
    }

    @Test fun promptNavigationReturnsToSettings() {
        val form = SynthesisSettingsForm(scope, { _, _ -> }, { _, _ -> }, { _ -> })
        var prompt by mutableStateOf(false)
        compose.setContent {
            DicteeTheme(false) {
                if (prompt) PromptScreen(form) { prompt = false }
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    SynthesisSection(SynthesisSettings(), form) { prompt = true }
                }
            }
        }
        compose.onNodeWithText("Prompt et format").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer le prompt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Tester").performScrollTo().assertIsDisplayed()
    }

    @Test fun promptFeedbackStaysVisibleForLongPrompt() {
        var fail = true
        val form = SynthesisSettingsForm(scope, { _, _ -> }, { _, _ -> }, { _ ->
            if (fail) error("synthetic provider error")
        })
        form.initialize(SynthesisSettings(prompt = "Long prompt\n".repeat(300)))
        compose.setContent { DicteeTheme(false) { PromptScreen(form, {}) } }
        compose.onNodeWithText("Enregistrer le prompt").performClick()
        compose.onNodeWithText("Impossible d’enregistrer le prompt. Réessaie.").assertIsDisplayed()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Enregistrer le prompt").performClick()
        compose.onNodeWithText("Prompt enregistré pour les prochaines synthèses.").assertIsDisplayed()
        compose.onNodeWithTag("prompt-text").performScrollTo().performTextClearance()
        compose.onNodeWithText("Enregistrer le prompt").performClick()
        compose.onNodeWithText("Le prompt ne peut pas être vide.").assertIsDisplayed()
    }

    @Test fun promptInsertsAtSelectionSavesAndRestoresDefaultInDarkTheme() {
        val saved = mutableListOf<SynthesisSettings>()
        val form = SynthesisSettingsForm(scope, { _, _ -> }, { _, _ -> }, { saved += SynthesisSettings(prompt = it) })
        form.initialize(SynthesisSettings())
        compose.setContent { DicteeTheme(true) { PromptScreen(form, {}) } }
        compose.onNodeWithTag("prompt-text").performScrollTo().performTextClearance()
        compose.onNodeWithTag("prompt-text").performTextInput("AB")
        compose.onNodeWithTag("prompt-text").performTextInputSelection(TextRange(1))
        listOf("{{type}}", "{{date}}", "{{duree}}", "{{intervenants}}").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.onNodeWithTag("prompt-text").performScrollTo().assertTextContains("A{{type}}{{date}}{{duree}}{{intervenants}}B")
        compose.onNodeWithText("Enregistrer le prompt").performClick()
        compose.runOnIdle { assertEquals("A{{type}}{{date}}{{duree}}{{intervenants}}B", saved.single().prompt) }
        compose.onNodeWithText("Rétablir le prompt par défaut").performScrollTo().performClick()
        compose.onNodeWithTag("prompt-text").performScrollTo().assertTextContains(SynthesisPrompt.DEFAULT)
        compose.onNodeWithText("Enregistrer le prompt").performClick()
        compose.runOnIdle { assertEquals(SynthesisPrompt.DEFAULT, saved.last().prompt) }
    }
}
