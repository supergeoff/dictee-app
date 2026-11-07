package top.supergeoff.dictee.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Dialogue de confirmation aux couleurs et à la police de l'app (`modal`) : un titre en `title-modal`, un corps facultatif, puis
 * « Annuler » en bouton secondaire et le bouton [confirm], principal ou, pour une suppression ([destructive]), de danger. Un toucher
 * hors du dialogue l'annule.
 */
@Composable
internal fun DicteeDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = Dictee.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (destructive) {
                DangerButton(text = confirm, enabled = confirmEnabled, onClick = onConfirm)
            } else {
                PrimaryButton(text = confirm, enabled = confirmEnabled, onClick = onConfirm)
            }
        },
        dismissButton = { SecondaryButton(text = "Annuler", onClick = onDismiss) },
        title = { Text(text = title, style = Dictee.type.titleModal, color = colors.text) },
        text = content,
        shape = CardShape,
        containerColor = colors.surface,
    )
}
