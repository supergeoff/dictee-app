package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.UnifiedDeletionPlan
import top.supergeoff.dictee.unifiedDeletionBody
import top.supergeoff.dictee.unifiedDeletionCanConfirm
import top.supergeoff.dictee.unifiedDeletionTitle

/** Confirmation de la suppression unifiée. Elle se ferme à la confirmation : aucune fenêtre de résultat ne suit. */
@Composable
internal fun UnifiedDeletionDialog(plan: UnifiedDeletionPlan, executing: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DicteeDialog(title = unifiedDeletionTitle(plan), confirm = "Supprimer", destructive = true,
        confirmEnabled = unifiedDeletionCanConfirm(plan, executing), dismissEnabled = !executing,
        onConfirm = onConfirm, onDismiss = onDismiss) {
        Column(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            unifiedDeletionBody(plan).forEach {
                Text(it, style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
    }
}
