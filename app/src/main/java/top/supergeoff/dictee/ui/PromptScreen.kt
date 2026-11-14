package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.SynthesisPrompt
import top.supergeoff.dictee.SynthesisSettingsForm

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptScreen(form: SynthesisSettingsForm, onBack: () -> Unit) {
    val draft by form.state.collectAsState()
    val enabled = draft.initialized && !draft.saving && !draft.testing
    var editor by remember(form) { mutableStateOf(TextFieldValue(draft.prompt)) }
    LaunchedEffect(draft.prompt) {
        if (editor.text != draft.prompt) editor = TextFieldValue(draft.prompt, TextRange(draft.prompt.length))
    }
    Column(Modifier.fillMaxSize().background(Dictee.colors.background)) {
        TopBar("Prompt et format", onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ces instructions et le format attendu sont envoyés dans le message system. Le VTT complet est envoyé séparément dans le message user.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("{{type}}", "{{date}}", "{{duree}}", "{{intervenants}}").forEach { variable ->
                    SecondaryButton(variable, enabled = enabled, mono = true) {
                        val start = editor.selection.min
                        val end = editor.selection.max
                        val text = editor.text.replaceRange(start, end, variable)
                        editor = TextFieldValue(text, TextRange(start + variable.length))
                        form.setPrompt(text)
                    }
                }
            }
            Text("Les valeurs sont tirées de l’enregistrement et du VTT. Les intervenants sont les étiquettes du VTT, pas des identités déduites.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            Text("Instructions et format Markdown", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(
                value = editor,
                onValueChange = { editor = it; form.setPrompt(it.text) },
                enabled = enabled,
                minHeight = 340.dp,
                modifier = Modifier.fillMaxWidth().testTag("prompt-text").semantics { contentDescription = "Instructions et format Markdown" },
            )
            SecondaryButton("Rétablir le prompt par défaut", enabled = enabled) {
                editor = TextFieldValue(SynthesisPrompt.DEFAULT, TextRange(SynthesisPrompt.DEFAULT.length))
                form.setPrompt(SynthesisPrompt.DEFAULT)
            }
            Text("Le nouveau prompt sera utilisé pour les prochaines synthèses. Les tâches déjà lancées gardent leurs instructions.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            draft.message?.let { Text(it, style = Dictee.type.bodySm, color = Dictee.colors.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            PrimaryButton(
                if (draft.saving) "Enregistrement…" else "Enregistrer le prompt",
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { form.submitPrompt() }
        }
    }
}
