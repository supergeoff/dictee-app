package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.BoxDeletionPlan
import top.supergeoff.dictee.BoxDeletionResult
import top.supergeoff.dictee.boxDeletionCanConfirm
import top.supergeoff.dictee.boxDeletionCountText
import top.supergeoff.dictee.boxDeletionResultText
import top.supergeoff.dictee.boxDeletionWarningText

@Composable
internal fun BoxDeletionDialog(plan: BoxDeletionPlan, executing: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DicteeDialog(title = "Supprimer du boîtier ?", confirm = "Supprimer", destructive = true,
        confirmEnabled = boxDeletionCanConfirm(plan, executing), dismissEnabled = !executing,
        onConfirm = onConfirm, onDismiss = onDismiss) {
        Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(boxDeletionCountText(plan.names.size), style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            Text("Les copies du téléphone et de Drive sont conservées.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            boxDeletionWarningText(plan.unbackedAudioCount)?.let {
                Text(it, style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
            if (plan.blockedNames.isNotEmpty()) {
                Text("Enregistrements occupés ou en attente : ${plan.blockedNames.sorted().joinToString()}. Arrête leur traitement ou retire-les de la file avant de les supprimer.",
                    style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
    }
}

@Composable
internal fun BoxDeletionStatus(executing: Boolean, result: BoxDeletionResult?, onDismiss: () -> Unit) {
    if (!executing && result == null) return
    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = { Text(if (executing) "Suppression du boîtier…" else "Résultat de la suppression", style = Dictee.type.titleModal, color = Dictee.colors.text) },
        text = {
            Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(if (executing) "Suppression et vérification en cours…" else boxDeletionResultText(result!!), style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        },
        confirmButton = { if (!executing) PrimaryButton("Fermer", onClick = onDismiss) },
        shape = CardShape, containerColor = Dictee.colors.surface,
    )
}
