package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.PipelineAction
import top.supergeoff.dictee.PipelinePresentation
import top.supergeoff.dictee.PipelineStage
import top.supergeoff.dictee.PipelineStatus

internal fun PipelineStage.title(): String = when (this) {
    PipelineStage.PHONE -> "Téléphone"
    PipelineStage.VTT -> "VTT"
    PipelineStage.MD -> "MD"
    PipelineStage.DRIVE -> "Drive"
}

@Composable
internal fun PipelineCard(presentation: PipelinePresentation, onAction: (PipelineAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().card().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                presentation.steps.forEach { step ->
                    val color = when (step.status) {
                        PipelineStatus.DONE -> Dictee.colors.success
                        PipelineStatus.ERROR -> Dictee.colors.destructive
                        PipelineStatus.RUNNING -> Dictee.colors.primary
                        PipelineStatus.WAITING -> Dictee.colors.warning
                        PipelineStatus.TODO -> if (step.reason != null) Dictee.colors.warning else Dictee.colors.textSecondary
                    }
                    Column(Modifier.weight(1f).semantics { contentDescription = "${step.stage.title()} : ${step.label}" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(22.dp).border(2.dp, color, CircleShape)
                            .background(if (step.status == PipelineStatus.DONE) color else Dictee.colors.surface, CircleShape))
                        Text(step.stage.title(), style = Dictee.type.captionStrong, color = Dictee.colors.text, textAlign = TextAlign.Center)
                        Text(step.label, style = Dictee.type.micro, color = color, textAlign = TextAlign.Center)
                    }
                }
            }
            presentation.reason?.let { reason ->
                Text("${reason.stage.title()} : ${reason.text}", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
        presentation.button?.let { action ->
            if (action.secondary) SecondaryButton(action.label, action.enabled, Modifier.fillMaxWidth()) { onAction(action.action) }
            else PrimaryButton(action.label, action.enabled, Modifier.fillMaxWidth()) { onAction(action.action) }
        }
    }
}
