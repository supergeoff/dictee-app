package top.supergeoff.dictee.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
                presentation.steps.forEachIndexed { index, step ->
                    val color = when (step.status) {
                        PipelineStatus.DONE -> Dictee.colors.success
                        PipelineStatus.ERROR -> Dictee.colors.destructive
                        PipelineStatus.RUNNING -> Dictee.colors.primary
                        PipelineStatus.WAITING -> Dictee.colors.warning
                        PipelineStatus.TODO -> if (step.reason != null) Dictee.colors.warning else Dictee.colors.textSecondary
                    }
                    Column(Modifier.weight(1f).semantics { contentDescription = "${step.stage.title()} : ${step.label}" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                            val lineColor = Dictee.colors.border
                            val success = Dictee.colors.success
                            val previousDone = index > 0 && presentation.steps[index - 1].status == PipelineStatus.DONE
                            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                                val gap = 18.dp.toPx()
                                if (index > 0) drawLine(if (previousDone) success.copy(alpha = .55f) else lineColor,
                                    androidx.compose.ui.geometry.Offset(0f, center.y), androidx.compose.ui.geometry.Offset(center.x - gap, center.y), 2.dp.toPx())
                                if (index < presentation.steps.lastIndex) drawLine(if (step.status == PipelineStatus.DONE) success.copy(alpha = .55f) else lineColor,
                                    androidx.compose.ui.geometry.Offset(center.x + gap, center.y), androidx.compose.ui.geometry.Offset(size.width, center.y), 2.dp.toPx())
                            }
                            val background = when (step.status) {
                                PipelineStatus.DONE -> Dictee.colors.successSurface
                                PipelineStatus.ERROR -> Dictee.colors.destructiveSurface
                                else -> Dictee.colors.surface
                            }
                            Box(Modifier.size(24.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
                                when (step.status) {
                                    PipelineStatus.DONE -> Text("✓", color = color, style = Dictee.type.captionStrong)
                                    PipelineStatus.ERROR -> Text("!", color = color, style = Dictee.type.captionStrong)
                                    else -> Canvas(Modifier.size(22.dp)) {
                                        val stroke = Stroke(1.5.dp.toPx(), pathEffect = if (step.status == PipelineStatus.WAITING) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
                                        if (step.status == PipelineStatus.RUNNING) drawArc(color, -90f, 260f, false, style = stroke)
                                        else drawCircle(color, style = stroke)
                                    }
                                }
                            }
                        }
                        Text(step.stage.title(), style = Dictee.type.captionStrong, color = Dictee.colors.text, textAlign = TextAlign.Center)
                        Text(step.label, style = Dictee.type.micro, color = color, textAlign = TextAlign.Center)
                    }
                }
            }
            presentation.reason?.let { reason ->
                val status = presentation.steps.first { it.stage == reason.stage }.status
                val background = when (status) {
                    PipelineStatus.ERROR -> Dictee.colors.destructiveSurface
                    PipelineStatus.WAITING -> Dictee.colors.warningSurface
                    else -> Dictee.colors.muted
                }
                Text("${reason.stage.title()} · ${reason.text}", modifier = Modifier.fillMaxWidth().background(background, RoundedCornerShape(10.dp)).padding(12.dp), style = Dictee.type.caption, color = Dictee.colors.text)
            }
        }
        presentation.button?.let { action ->
            if (action.secondary) SecondaryButton(action.label, action.enabled, Modifier.fillMaxWidth()) { onAction(action.action) }
            else PrimaryButton(action.label, action.enabled, Modifier.fillMaxWidth()) { onAction(action.action) }
        }
    }
}
