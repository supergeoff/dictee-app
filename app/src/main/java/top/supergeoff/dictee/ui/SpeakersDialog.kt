package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.R
import top.supergeoff.dictee.SpeakerLabel
import top.supergeoff.dictee.SpeakerName
import top.supergeoff.dictee.initialInput
import top.supergeoff.dictee.shownOrigin
import top.supergeoff.dictee.speakerRenameDraft
import top.supergeoff.dictee.speakerSuggestions

/**
 * Dialogue « Locuteurs » : une ligne par locuteur de [labels], qui montre son origine (« A »), un bouton lecture qui lance l'extrait au
 * premier segment du nom courant ([firstCueMs], avec le lecteur [playback] s'il existe) et un champ de nom, prérempli avec le nom courant
 * quand il diffère de l'origine. Un champ vide vise l'origine : vider un locuteur nommé le ramène à son origine. Le bouton ✕ du champ vide la
 * saisie. Le champ qui a le focus propose les noms du carnet [book] filtrés par sa saisie. « Enregistrer » envoie à [onSave] les
 * correspondances locuteur vers nom (ou origine rétablie) ; il reste désactivé sans changement ou avec un nom invalide. Les locuteurs qui
 * finiraient sous le même nom sont annoncés comme fusionnés.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakersDialog(
    labels: List<SpeakerLabel>,
    firstCueMs: Map<String, Long>,
    book: List<SpeakerName>,
    playback: Playback?,
    onSave: (Map<SpeakerLabel, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val inputs = remember { mutableStateMapOf<SpeakerLabel, String>().apply { labels.forEach { put(it, it.initialInput) } } }
    var focused by remember { mutableStateOf<SpeakerLabel?>(null) }
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
                        Text(label.shownOrigin, style = Dictee.type.label, color = Dictee.colors.text, maxLines = 1, modifier = Modifier.widthIn(min = 16.dp))
                        if (canPlay) IconAction(R.drawable.ic_play, "Écouter un extrait de ${label.shownOrigin}") { playback?.playFrom(firstCueMs[label.current] ?: 0L) }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val query = inputs[label].orEmpty()
                        DicteeTextField(
                            query, { inputs[label] = it }, placeholder = "(laisser ${label.shownOrigin})",
                            keyboardOptions = KeyboardOptions(imeAction = if (label == labels.last()) ImeAction.Done else ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) focused = label else if (focused == label) focused = null }
                                .semantics { contentDescription = "Nom de ${label.shownOrigin}" },
                            trailing = if (query.isEmpty()) null else {
                                {
                                    Box(
                                        modifier = Modifier.size(28.dp).clip(ControlShape).pressable { inputs[label] = "" },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_close), "Effacer le nom de ${label.shownOrigin}",
                                            modifier = Modifier.size(16.dp), tint = Dictee.colors.textSecondary,
                                        )
                                    }
                                }
                            },
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
