package top.supergeoff.dictee.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.supergeoff.dictee.FileKind
import top.supergeoff.dictee.DriveSendRequest
import top.supergeoff.dictee.DriveSync
import top.supergeoff.dictee.UiState

@Composable
internal fun DriveSendDialog(ui: UiState, request: DriveSendRequest, onConfirm: (Set<FileKind>, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var choice by remember(request) { mutableStateOf(request.formats) }
    val summary = DriveSync.dialogSummary(ui.recordings, ui.drive, ui.local, ui.documentHashes, request, choice)
    val recordings = summary.sendable
    val missingVtt = summary.missingVtt
    val missingMd = summary.missingMd
    val folder = ui.local.knownFolders.firstOrNull { it.id == ui.local.chosenFolderId }?.name
        ?: ui.local.chosenFolderId ?: "à résoudre avant l'envoi"
    DicteeDialog(title = "Envoyer sur Drive", confirm = "Envoyer", onConfirm = { onConfirm(choice, recordings.map { it.name }.toSet()) }, onDismiss = onDismiss,
        confirmEnabled = recordings.isNotEmpty()) {
        Column {
            Text("Compte : ${ui.local.driveAccount ?: "indisponible"}", style = Dictee.type.body, color = Dictee.colors.text)
            Text("Dossier : $folder", style = Dictee.type.body, color = Dictee.colors.text)
            Text("${recordings.size} enregistrement(s)", style = Dictee.type.body, color = Dictee.colors.text)
            for (kind in FileKind.entries) {
                val checked = kind in choice
                val toggle = { choice = if (checked) choice - kind else choice + kind }
                Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Text(kind.name, style = Dictee.type.body, color = Dictee.colors.text)
                }
            }
            if (missingVtt > 0 || missingMd > 0) Text("Documents manquants : " + listOfNotNull(if (FileKind.VTT in choice) "$missingVtt VTT" else null, if (FileKind.MD in choice) "$missingMd MD" else null).joinToString(", ") + ".", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}
