package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.PhoneDeletionPlan

@Composable
internal fun PhoneDeletionDialog(
    plan: PhoneDeletionPlan,
    executing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DicteeDialog(
        title = "Supprimer du téléphone ?",
        confirm = "Supprimer",
        destructive = true,
        confirmEnabled = !executing && plan.recordings.isNotEmpty() && plan.blockedNames.isEmpty(),
        dismissEnabled = !executing,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${plan.recordings.size} enregistrement(s)", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            if (plan.documentsUnverified) {
                Text("La sauvegarde des documents VTT ou MD sur Drive ne peut pas être vérifiée.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
            if (plan.audioAtRisk) {
                Text("L'audio risque d'être perdu : aucune autre copie n'est connue.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
            if (plan.blockedNames.isNotEmpty()) {
                Text("Certains enregistrements sont occupés : ${plan.blockedNames.sorted().joinToString()}. Arrête leur traitement ou retire-les de la file avant de les supprimer.",
                    style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
    }
}
