package top.supergeoff.dictee.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.SpeakerName
import top.supergeoff.dictee.SpeakerNames
import top.supergeoff.dictee.speakerMergeNotice
import top.supergeoff.dictee.speakerNameChoices

/**
 * Dialogue « Qui est <[label]> ? » : écoute d'un extrait au premier segment de l'étiquette ([firstCueMs], avec le lecteur [playback] s'il existe),
 * saisie d'un nom, puces du carnet [book] et « + Ajouter » pour un nom nouveau. Toucher une puce ou l'entrée « + Ajouter » valide ce nom ;
 * « Valider » valide la saisie. Un nom déjà donné à une autre étiquette de [labels] demande d'abord une confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerDialog(
    label: String,
    firstCueMs: Long,
    labels: List<String>,
    book: List<SpeakerName>,
    playback: Playback?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var merging by remember { mutableStateOf<String?>(null) }
    val valid = SpeakerNames.validate(query)
    val choices = speakerNameChoices(book, query)
    fun submit(name: String) {
        if (speakerMergeNotice(labels, label, name) != null) merging = name else onConfirm(name)
    }
    merging?.let { name ->
        DicteeDialog(
            title = "Fusionner les locuteurs ?", confirm = "Fusionner",
            onConfirm = { merging = null; onConfirm(name) }, onDismiss = { merging = null },
        ) {
            Text(speakerMergeNotice(labels, label, name).orEmpty(), style = Dictee.type.body, color = Dictee.colors.textSecondary)
        }
    }
    DicteeDialog(
        title = "Qui est $label ?", confirm = "Valider", confirmEnabled = valid != null,
        onConfirm = { valid?.let(::submit) }, onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                "Écouter un extrait (${formatClock(firstCueMs.toInt())})",
                enabled = playback != null && playback.status != PlaybackStatus.FAILED,
                modifier = Modifier.fillMaxWidth(),
            ) { playback?.playFrom(firstCueMs) }
            DicteeTextField(
                query, { query = it }, placeholder = "Rechercher ou saisir un nom…",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { valid?.let(::submit) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nom du locuteur" },
            )
            if (choices.suggestions.isNotEmpty() || choices.addName != null) {
                Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.suggestions.isNotEmpty() && query.isBlank()) {
                        Text("Récents", style = Dictee.type.label, color = Dictee.colors.text)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.suggestions.forEach { FilterPill(it.name, selected = false, onClick = { submit(it.name) }) }
                        choices.addName?.let { FilterPill("+ Ajouter « $it »", selected = false, onClick = { submit(it) }) }
                    }
                }
            }
        }
    }
}
