package top.supergeoff.dictee

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.PipelineCard

class PipelineCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stagesAndReasonAreAccessibleAndDisabledActionCannotRun() {
        var calls = 0
        val steps = listOf(
            PipelineStep(PipelineStage.PHONE, PipelineStatus.DONE, "Fait"),
            PipelineStep(PipelineStage.VTT, PipelineStatus.ERROR, "Envoi incertain"),
            PipelineStep(PipelineStage.MD, PipelineStatus.RUNNING, "Synthèse"),
            PipelineStep(PipelineStage.DRIVE, PipelineStatus.WAITING, "En attente"),
        )
        val presentation = PipelinePresentation(steps, PipelineReason(PipelineStage.VTT, "Risque de doublon"),
            PipelineButton(PipelineAction.RETRY_VTT, "Relance indisponible", enabled = false), "Erreur", null)
        compose.setContent { DicteeTheme(false) { PipelineCard(presentation) { calls++ } } }
        compose.onNodeWithContentDescription("Téléphone : Fait").assertExists()
        compose.onNodeWithContentDescription("VTT : Envoi incertain").assertExists()
        compose.onNodeWithContentDescription("MD : Synthèse").assertExists()
        compose.onNodeWithContentDescription("Drive : En attente").assertExists()
        compose.onNodeWithText("VTT · Risque de doublon").assertExists()
        compose.onNodeWithText("Relance indisponible").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
