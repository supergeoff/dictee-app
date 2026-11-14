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
import top.supergeoff.dictee.ui.PipelineCard

class SynthesisRecordingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsKeepTranscriptWithoutActions() {
        compose.setContent {
            DicteeTheme(true) {
                RecordingDocuments(markdown = null, provenanceLabel = null, hasVtt = true) {
                    Text("Segment synthétique")
                }
            }
        }
        compose.onNodeWithText("La synthèse reste à créer").assertIsDisplayed()
        compose.onNodeWithText("Créer le MD").assertDoesNotExist()
        compose.onNodeWithText("Transcription").performClick()
        compose.onNodeWithText("Segment synthétique").assertIsDisplayed()
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
                RecordingMenu(canRecover = false, onRecover = {}, canRedoVtt = true, canRedoMd = false,
                    onRedoVtt = { actions += "vtt" }, onRedoMd = { actions += "md" })
            }
        }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Refaire le MD").assertIsNotEnabled()
        compose.onNodeWithText("Refaire le VTT et le MD").performClick()
        compose.runOnIdle { assertEquals(listOf("vtt"), actions) }
        compose.onNodeWithContentDescription("Plus d'actions").performClick()
        compose.onNodeWithText("Annuler la synthèse").assertDoesNotExist()
    }

    @Test fun pipelineOffersOneActionAndTheReason() {
        val steps = PipelineStage.entries.map { PipelineStep(it, PipelineStatus.TODO, "À faire") }
        var action: PipelineAction? = null
        val presentation = PipelinePresentation(steps, PipelineReason(PipelineStage.MD, "Synthèse suspendue."),
            PipelineButton(PipelineAction.RETRY_MD, "Relancer le MD"), "Erreur", null)
        compose.setContent { DicteeTheme(true) { PipelineCard(presentation) { action = it } } }
        compose.onNodeWithText("Synthèse suspendue.").assertIsDisplayed()
        compose.onNodeWithText("Relancer le MD").performClick()
        compose.runOnIdle { assertEquals(PipelineAction.RETRY_MD, action) }
    }

    @Test fun renderedMarkdownShowsModelInLightTheme() {
        compose.setContent {
            DicteeTheme(false) {
                androidx.compose.foundation.layout.Column() {
                RecordingDocuments(markdown = "## Résumé\n\n- Élément synthétique", provenanceLabel = "Modèle utilisé : modèle-démo") {
                    Text("Segment synthétique")
                }
                }
            }
        }
        compose.onNodeWithText("Modèle utilisé : modèle-démo").assertIsDisplayed()
        compose.onNodeWithText("Transcription").performClick()
        compose.onNodeWithText("Segment synthétique").assertIsDisplayed()
    }
}
