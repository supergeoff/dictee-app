package top.supergeoff.dictee.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight

/**
 * Dialogue de confirmation aux couleurs et à la police de l'app : un titre, un corps facultatif, puis « Annuler » et le bouton
 * [confirm]. Un toucher hors du dialogue l'annule.
 */
@Composable
internal fun DicteeDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = Dictee.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(text = confirm, style = sized(14, 20, FontWeight.SemiBold))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Annuler", style = sized(14, 20, FontWeight.Medium)) }
        },
        title = { Text(text = title, style = sized(18, 24, FontWeight.SemiBold), color = colors.text) },
        text = content,
        shape = CardShape,
        containerColor = colors.surface,
    )
}
