package top.supergeoff.dictee

import android.text.Spanned
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.HeadingSpan
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.RecordingDocuments
import top.supergeoff.dictee.ui.MarkdownView
import top.supergeoff.dictee.ui.RecordingMenu
import top.supergeoff.dictee.ui.SynthesisTaskNotice

class SynthesisRecordingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsKeepTranscriptAndCreateMissingMdInBothThemes() {
        var creates = 0
        compose.setContent {
            DicteeTheme(true) {
                RecordingDocuments(markdown = null, provenanceLabel = null, canCreate = true, onCreate = { creates++ }) {
                    Text("Segment synthétique")
                }
            }
        }
        compose.onNodeWithText("Créer le MD").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, creates) }
        compose.onNodeWithText("Transcription").performClick()
        compose.onNodeWithText("Segment synthétique").assertIsDisplayed()
        compose.onNodeWithText("Synthèse").performClick()
        compose.onNodeWithText("Créer le MD").assertIsDisplayed()
    }

    @Test fun markdownIsRenderedAsNativeSpansWithoutRawMarkers() {
        lateinit var activity: android.app.Activity
        compose.setContent {
            activity = requireNotNull(LocalActivity.current)
            DicteeTheme(false) { MarkdownView("## Résumé\n\n- Élément synthétique") }
        }
        compose.runOnIdle {
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val text = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .first { it.text.toString().contains("Élément synthétique") }.text as Spanned
            assertFalse(text.toString().contains("##"))
            assertTrue(text.getSpans(0, text.length, HeadingSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, BulletListItemSpan::class.java).isNotEmpty())
        }
    }

    @Test fun menuExposesExplicitActionsAndDisabledMdWithoutVtt() {
        val actions = mutableListOf<String>()
        compose.setContent {
            DicteeTheme(false) {
                RecordingMenu(canRecover = false, onRecover = {}, canCancelTranscription = true, cancellingSynthesis = true,
                    onCancelTranscription = { actions += "annuler" }, canRedoVtt = true, canRedoMd = false,
                    onRedoVtt = { actions += "vtt" }, onRedoMd = { actions += "md" })
            }
        }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Refaire le MD").assertIsNotEnabled()
        compose.onNodeWithText("Refaire le VTT et le MD").performClick()
        compose.runOnIdle { assertEquals(listOf("vtt"), actions) }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Annuler la synthèse").performClick()
        compose.runOnIdle { assertEquals(listOf("vtt", "annuler"), actions) }
    }

    @Test fun suspendedSynthesisOffersManualResumeWithSameTaskId() {
        var retried: String? = null
        val context = SynthesisContext("https://example.invalid/v1", "ref", "model", "prompt", PromptMetadata("Note", "date", "durée"), "prompt résolu", "vtt-hash")
        val work = SynthesisWork(SynthesisPhase.SUSPENDED, "Note-20261101000000.md", true, context, 3, null, null, "Trois tentatives ont échoué.")
        val task = TranscriptionTask("id-synthétique", "20261101000000", "Note-20261101000000.mp3", "Note-20261101000000.vtt", "content://folder", "", "", "", "", "", TranscriptionPhase.SYNTHESIZING, suspended = true, synthesis = work)
        compose.setContent { DicteeTheme(true) { SynthesisTaskNotice(task) { retried = it } } }
        compose.onNodeWithText("Trois tentatives ont échoué.").assertIsDisplayed()
        compose.onNodeWithText("Relancer le MD").performClick()
        compose.runOnIdle { assertEquals("id-synthétique", retried) }
    }

    @Test fun taskNoticeShowsCallingRetryAndSavingAsDistinctStates() {
        val work = SynthesisWork(SynthesisPhase.CALLING, "Note.md", false, null, 1, 60_000, null, null)
        val task = TranscriptionTask("id", "20261101000000", "Note.mp3", "Note.vtt", "content://folder", "", "", "", "", "", TranscriptionPhase.SYNTHESIZING, synthesis = work)
        val current = mutableStateOf(task)
        compose.setContent { DicteeTheme(false) { SynthesisTaskNotice(current.value) {} } }
        compose.onNodeWithText("Synthèse en cours. Le MD sera enregistré dès réception.").assertIsDisplayed()
        compose.onNodeWithText("Relance automatique prévue. La tâche est conservée.").assertDoesNotExist()
        compose.runOnIdle { current.value = task.copy(synthesis = work.copy(phase = SynthesisPhase.READY)) }
        compose.onNodeWithText("Relance automatique prévue. La tâche est conservée.").assertIsDisplayed()
        compose.runOnIdle { current.value = task.copy(synthesis = work.copy(phase = SynthesisPhase.SAVE_PENDING, nextAttemptAtMillis = null)) }
        compose.onNodeWithText("Enregistrement de la synthèse en cours.").assertIsDisplayed()
    }

    @Test fun renderedMarkdownShowsModelInLightTheme() {
        compose.setContent {
            DicteeTheme(false) {
                RecordingDocuments(markdown = "## Résumé\n\n- Élément synthétique", provenanceLabel = "Modèle utilisé : modèle-démo", canCreate = false, onCreate = {}) {
                    Text("Segment synthétique")
                }
            }
        }
        compose.onNodeWithText("Modèle utilisé : modèle-démo").assertIsDisplayed()
        compose.onNodeWithText("Transcription").performClick()
        compose.onNodeWithText("Segment synthétique").assertIsDisplayed()
    }
}
