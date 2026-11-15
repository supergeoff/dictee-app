package top.supergeoff.dictee.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.supergeoff.dictee.DriveSendChoice
import top.supergeoff.dictee.DriveSendRequest
import top.supergeoff.dictee.DriveSync
import top.supergeoff.dictee.Recordings
import top.supergeoff.dictee.UiState

@Composable
internal fun DriveSendDialog(ui: UiState, request: DriveSendRequest, onConfirm: (DriveSendChoice) -> Unit, onDismiss: () -> Unit) {
    var choice by remember(request) { mutableStateOf(request.choice) }
    val selected = if (request.names == null) Recordings.toSendCurrent(ui.recordings, ui.drive, ui.local.copy(driveSendChoice = choice), ui.documentHashes)
        else Recordings.sendable(ui.recordings, ui.local.errors).filter { it.name in request.names }
    val initialSelection = if (request.names == null)
        Recordings.toSendCurrent(ui.recordings, ui.drive, ui.local.copy(driveSendChoice = request.choice), ui.documentHashes)
    else selected
    val summary = DriveSync.sendSummary(selected, choice)
    val warnings = DriveSync.sendSummary(initialSelection, choice)
    val recordings = summary.sendable
    val missingVtt = warnings.missingVtt
    val missingMd = warnings.missingMd
    val folder = ui.local.knownFolders.firstOrNull { it.id == ui.local.chosenFolderId }?.name
        ?: ui.local.chosenFolderId ?: "à résoudre avant l'envoi"
    DicteeDialog(title = "Envoyer sur Drive", confirm = "Envoyer", onConfirm = { onConfirm(choice) }, onDismiss = onDismiss,
        confirmEnabled = recordings.isNotEmpty()) {
        Column {
            Text("Compte : ${ui.local.driveAccount ?: "indisponible"}", style = Dictee.type.body, color = Dictee.colors.text)
            Text("Dossier : $folder", style = Dictee.type.body, color = Dictee.colors.text)
            Text("${recordings.size} enregistrement(s)", style = Dictee.type.body, color = Dictee.colors.text)
            for ((value, label) in listOf(DriveSendChoice.ALL_FILES to "MP3 + VTT + MD", DriveSendChoice.DOCUMENTS_ONLY to "VTT + MD")) {
                Row(Modifier.fillMaxWidth().clickable { choice = value }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = choice == value, onClick = { choice = value })
                    Text(label, style = Dictee.type.body, color = Dictee.colors.text)
                }
            }
            if (missingVtt > 0 || missingMd > 0) Text("Documents manquants : $missingVtt VTT, $missingMd MD.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}
