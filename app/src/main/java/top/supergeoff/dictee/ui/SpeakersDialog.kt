package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.R
import top.supergeoff.dictee.SpeakerName
import top.supergeoff.dictee.speakerRenameDraft
import top.supergeoff.dictee.speakerSuggestions

/**
 * Dialogue « Locuteurs » : une ligne par étiquette de [labels], avec un bouton lecture qui lance l'extrait au premier segment de l'étiquette
 * ([firstCueMs], avec le lecteur [playback] s'il existe) et un champ de nom. Un champ vide laisse l'étiquette. Le champ qui a le focus
 * propose les noms du carnet [book] filtrés par sa saisie. « Enregistrer » envoie à [onSave] les correspondances étiquette vers nom ;
 * il reste désactivé sans changement ou avec un nom invalide. Les étiquettes qui finiraient sous le même nom sont annoncées comme fusionnées.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakersDialog(
    labels: List<String>,
    firstCueMs: Map<String, Long>,
    book: List<SpeakerName>,
    playback: Playback?,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val inputs = remember { mutableStateMapOf<String, String>() }
    var focused by remember { mutableStateOf<String?>(null) }
    val draft = speakerRenameDraft(labels, inputs)
    val canPlay = playback != null && playback.status != PlaybackStatus.FAILED
    DicteeDialog(
        title = "Locuteurs", confirm = "Enregistrer", confirmEnabled = draft.canSave,
        onConfirm = { if (draft.canSave) onSave(draft.renames) }, onDismiss = onDismiss,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            labels.forEach { label ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Row(modifier = Modifier.widthIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = Dictee.type.label, color = Dictee.colors.text, maxLines = 1, modifier = Modifier.widthIn(min = 16.dp))
                        if (canPlay) IconAction(R.drawable.ic_play, "Écouter un extrait de $label") { playback?.playFrom(firstCueMs[label] ?: 0L) }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val query = inputs[label].orEmpty()
                        DicteeTextField(
                            query, { inputs[label] = it }, placeholder = "(laisser $label)",
                            keyboardOptions = KeyboardOptions(imeAction = if (label == labels.last()) ImeAction.Done else ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) focused = label else if (focused == label) focused = null }
                                .semantics { contentDescription = "Nom de $label" },
                        )
                        val suggestions = if (focused == label) speakerSuggestions(book, query) else emptyList()
                        if (suggestions.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                suggestions.forEach { FilterPill(it.name, selected = false, onClick = { inputs[label] = it.name }) }
                            }
                        }
                    }
                }
            }
            if (draft.invalidLabels.isNotEmpty()) {
                Text(
                    "Un nom compte 40 caractères au plus, sans « < », « > », « & » ni saut de ligne.",
                    style = Dictee.type.caption, color = Dictee.colors.destructive,
                )
            }
            draft.mergeNotices.forEach { Text(it, style = Dictee.type.caption, color = Dictee.colors.textSecondary) }
            Text("Le MD sera refait avec ces noms.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}
