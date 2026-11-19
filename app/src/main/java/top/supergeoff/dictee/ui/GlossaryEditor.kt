package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.ComplementaryGlossary
import top.supergeoff.dictee.TranscriptionSettingsForm

/** Les bulles sont une représentation de lignes, pas un format réseau distinct. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlossaryEditor(form: TranscriptionSettingsForm) {
    val draft by form.state.collectAsState()
    val enabled = draft.initialized && !draft.saving
    val entries = ComplementaryGlossary.entries(draft.glossary)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Glossaire complémentaire", style = Dictee.type.label, color = Dictee.colors.text)
        Text("Ajoute un terme par ligne, par exemple : Terme (souvent entendu : a, b). Ce vocabulaire complète le glossaire du serveur s’il en possède un, sinon il sert d’indice de vocabulaire.",
            style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        DicteeTextField(draft.glossaryInput, form::setGlossaryInput,
            enabled = enabled, placeholder = "Ajouter un terme",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { form.addGlossaryEntry() }),
            modifier = Modifier.fillMaxWidth().testTag("glossary-input").semantics { contentDescription = "Terme du glossaire complémentaire" })
        SecondaryButton("Ajouter", enabled = enabled && draft.glossaryInput.isNotBlank()) { form.addGlossaryEntry() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(Tokens.RADIUS_DP.dp)).background(Dictee.colors.secondary),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry, style = Dictee.type.bodySm, color = Dictee.colors.text,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp, top = 8.dp, bottom = 8.dp).testTag("glossary-entry-$index"))
                    Box(modifier = Modifier.minimumInteractiveComponentSize().clip(ControlShape)
                        .pressable(enabled = enabled, onClick = { form.removeGlossaryEntry(index) })
                        .padding(12.dp).testTag("glossary-remove-$index")
                        .semantics { contentDescription = "Retirer $entry" }, contentAlignment = Alignment.Center) {
                        Text("×", style = Dictee.type.body, color = Dictee.colors.textSecondary)
                    }
                }
            }
        }
        Text("${entries.size} / 500 lignes · ${draft.glossary.codePointCount(0, draft.glossary.length)} / 20 000 caractères",
            style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        if (ComplementaryGlossary.hasAdvisory(ComplementaryGlossary.append(draft.glossary, draft.glossaryInput))) {
            Text("Certains services ignorent les lignes de plus de cinq mots ou terminées par un point, un point d’exclamation ou un point d’interrogation. Le texte reste transmis tel quel.",
                style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}
