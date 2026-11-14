package top.supergeoff.dictee.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import top.supergeoff.dictee.transcriptionTaskSettingsMessages
import top.supergeoff.dictee.TranscriptionSettingsForm
import top.supergeoff.dictee.SynthesisSettings
import top.supergeoff.dictee.SynthesisSettingsForm
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.BuildConfig
import top.supergeoff.dictee.DriveClient
import top.supergeoff.dictee.LocalState
import top.supergeoff.dictee.R
import top.supergeoff.dictee.ThemeChoice
import top.supergeoff.dictee.UiState

private val SegmentShape = RoundedCornerShape(Tokens.RADIUS_INNER_DP.dp)

/** Les trois choix du thème, dans l'ordre des boutons segmentés. */
private val Themes = listOf(ThemeChoice.LIGHT to "Clair", ThemeChoice.DARK to "Sombre", ThemeChoice.SYSTEM to "Système")

/**
 * Réglages découpés en rubriques, dont les services de transcription et de synthèse indépendants.
 * L'écran n'affiche que [ui] ; ce que demandent les boutons passe par [model] ou par les rappels de l'activité.
 */
@Composable
fun SettingsScreen(
    ui: UiState,
    model: AppModel,
    onBack: () -> Unit,
    onAssociate: () -> Unit,
    onDriveFolders: () -> Unit,
    onJournal: () -> Unit,
    onPrompt: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        TopBar("Réglages", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BoxSection(ui.local, busy = ui.recovering, onAssociate = onAssociate)
            StorageSection(ui.phoneFolder, busy = ui.recovering || ui.sending, onChange = model::choosePhoneFolder)
            DriveSection(ui.local, model, onDriveFolders)
            TranscriptionSection(ui.local, model.transcriptionForm)
            SynthesisSection(ui.local.synthesisSettings, model.synthesisForm, onPrompt)
            AppearanceSection(ui.local.theme, model::setTheme)
            AboutSection(onJournal)
        }
    }
}

/**
 * Le boîtier associé avec son adresse. Sans boîtier, le bouton devient « Associer » : les réglages s'ouvrent aussi avant l'association.
 * Pendant une récupération ([busy]), le bouton est inactif : la recherche d'un boîtier fermerait la liaison que la récupération utilise.
 */
@Composable
private fun BoxSection(local: LocalState, busy: Boolean, onAssociate: () -> Unit) {
    val associated = local.deviceAddress != null
    Section("Boîtier") {
        GroupCard {
            ListRow(
                title = if (associated) local.deviceName ?: "MECHEN" else "Aucun boîtier",
                subtitle = local.deviceAddress,
                monoSubtitle = true,
                tile = { IconTile(R.drawable.ic_recorder) },
            ) {
                CompactButton(text = if (associated) "Changer" else "Associer", primary = !associated, enabled = !busy, onClick = onAssociate)
            }
        }
    }
}

/** Le dossier du téléphone. Pendant une récupération ou un envoi ([busy]), « Changer » est inactif : le travail en cours utilise le droit sur ce dossier. */
@Composable
private fun StorageSection(folder: String?, busy: Boolean, onChange: () -> Unit) {
    Section("Stockage sur le téléphone") {
        GroupCard {
            ListRow(title = "Dossier", subtitle = folder ?: "Aucun dossier", tile = { IconTile(R.drawable.ic_folder) }) {
                CompactButton(text = "Changer", enabled = !busy, onClick = onChange)
            }
        }
    }
}

/**
 * L'interrupteur d'envoi ; les lignes Compte et Dossier ne s'affichent que s'il est activé. Le compte est l'adresse lue dans Drive une fois
 * l'accès accordé : un état repris de la version 0.1.5 peut avoir l'envoi activé sans compte, d'où « Non autorisé » jusqu'à cette lecture.
 */
@Composable
private fun DriveSection(local: LocalState, model: AppModel, onFolders: () -> Unit) {
    Section("Google Drive") {
        GroupCard {
            ListRow(
                title = "Envoyer vers Drive",
                modifier = Modifier.toggleable(value = local.driveEnabled, role = Role.Switch, onValueChange = model::setDriveEnabled),
                weight = FontWeight.Normal,
            ) {
                Toggle(local.driveEnabled)
            }
            if (local.driveEnabled) {
                RowDivider()
                ListRow(title = "Compte", subtitle = local.driveAccount ?: "Non autorisé") {
                    TextAction("Déconnecter", model::disconnectGoogle)
                }
                RowDivider()
                ListRow(title = "Dossier", subtitle = chosenFolderName(local), onClick = onFolders) { Chevron() }
            }
        }
    }
}

/** Nom du dossier de dépôt : celui du dossier choisi, « Dictée » tant qu'aucun n'est choisi. */
private fun chosenFolderName(local: LocalState): String =
    local.knownFolders.firstOrNull { it.id == local.chosenFolderId }?.name ?: DriveClient.DEFAULT_FOLDER

/**
 * `switch` : 44 sur 24 dp, sans réponse propre, c'est la rangée qui bascule (et qui donne la zone tactile). La piste est `primary` allumée et
 * `switch-bg-off` éteinte ; la pastille de 18 dp est toujours sur `card`, avec `shadow-pressed`.
 */
@Composable
private fun Toggle(checked: Boolean) {
    val colors = Dictee.colors
    Box(
        modifier = Modifier
            .size(width = Tokens.SWITCH_WIDTH_DP.dp, height = Tokens.SWITCH_HEIGHT_DP.dp)
            .clip(CircleShape)
            .background(if (checked) colors.primary else colors.switchOff)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .softShadow(CircleShape, elevation = 2.dp, lightOnly = false)
                .clip(CircleShape)
                .background(colors.surface),
        )
    }
}

/** Action discrète au bout d'une rangée, comme « Déconnecter » : le bouton fantôme, sans fond, en `muted-foreground`. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(Tokens.BUTTON_HEIGHT_COMPACT_DP.dp)
            .clip(ControlShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Dictee.type.controlSm, color = Dictee.colors.textSecondary, maxLines = 1)
    }
}

/** Le formulaire reste dans le modèle pendant la sauvegarde et ne préremplit jamais la clé. */
@Composable
private fun TranscriptionSection(local: LocalState, form: TranscriptionSettingsForm) {
    LaunchedEffect(form) { form.initialize(local.transcriptionSettings) }
    val draft by form.state.collectAsState()
    val settings = local.transcriptionSettings
    Section("Transcription") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (local.transcriptionAddressNeedsReview) {
                Text("L’ancienne adresse de transcription était invalide. Saisis une adresse de serveur et enregistre les réglages.", color = Dictee.colors.textSecondary)
            }
            OutlinedTextField(draft.baseUrl, form::setBaseUrl, enabled = draft.initialized && !draft.saving, label = { Text("Adresse du serveur (/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.secret, form::setSecret, enabled = draft.initialized && !draft.saving, label = { Text("Nouvelle clé") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text(if (settings.credentialRef == null) "Aucune clé enregistrée" else "Une clé est enregistrée. Laisse ce champ vide pour la conserver.", color = Dictee.colors.textSecondary)
            OutlinedTextField(draft.language, form::setLanguage, enabled = draft.initialized && !draft.saving, label = { Text("Langue") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.model, form::setModel, enabled = draft.initialized && !draft.saving, label = { Text("Modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { form.submit() }, enabled = draft.initialized && !draft.saving) { Text(if (draft.saving) "Enregistrement…" else "Enregistrer") }
            draft.message?.let { Text(it, color = Dictee.colors.textSecondary) }
            transcriptionTaskSettingsMessages(local.transcriptionTasks).forEach { message ->
                Text(message, color = Dictee.colors.textSecondary)
            }
            Text("Le test de connexion sera disponible ultérieurement.", color = Dictee.colors.textSecondary)
        }
    }
}

@Composable
internal fun SynthesisSection(settings: SynthesisSettings, form: SynthesisSettingsForm, onPrompt: () -> Unit) {
    LaunchedEffect(form) { form.initialize(settings) }
    val draft by form.state.collectAsState()
    val enabled = draft.initialized && !draft.saving && !draft.testing
    Section("Synthèse") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Service compatible OpenAI. Le VTT et le prompt sont envoyés au service que tu configures.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            OutlinedTextField(draft.baseUrl, form::setBaseUrl, enabled = enabled, label = { Text("Adresse du serveur (/v1)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("synthesis-address"), textStyle = Dictee.type.body)
            OutlinedTextField(draft.secret, form::setSecret, enabled = enabled, label = { Text("Nouvelle clé") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("synthesis-secret"), textStyle = Dictee.type.body)
            Text(if (settings.credentialRef == null) "Aucune clé enregistrée. La clé est protégée par Android Keystore." else "Une clé est enregistrée. Laisse ce champ vide pour la conserver. La clé est protégée par Android Keystore.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            OutlinedTextField(draft.model, form::setModel, enabled = enabled, label = { Text("Modèle") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("synthesis-model"), textStyle = Dictee.type.body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (draft.testing) "Test…" else "Tester", enabled = enabled, modifier = Modifier.weight(1f)) { form.test() }
                PrimaryButton(if (draft.saving) "Enregistrement…" else "Enregistrer", enabled = enabled, modifier = Modifier.weight(1f).testTag("synthesis-save")) { form.submit() }
            }
            Text("« Tester » envoie une courte requête au modèle et peut entraîner un coût. Ce test ne vérifie pas qu’un VTT de deux heures tient dans son contexte.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            draft.message?.let { Text(it, style = Dictee.type.bodySm, color = Dictee.colors.text) }
            GroupCard { ListRow(title = "Prompt et format", subtitle = "Instructions et structure du Markdown", enabled = enabled, onClick = onPrompt) { Chevron() } }
            Text("En cas d’interruption, la tâche est conservée. Dictée effectue au plus deux relances automatiques, puis attend une relance manuelle. Une tentative interrompue peut être facturée.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}

/** Le thème se choisit parmi trois boutons segmentés (`theme-toggle`) ; « Système » suit le réglage d'Android. */
@Composable
private fun AppearanceSection(selected: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    val colors = Dictee.colors
    Section("Apparence") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ElementShape)
                .background(colors.muted)
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Themes.forEach { (choice, label) -> ThemeButton(label, choice == selected) { onSelect(choice) } }
        }
    }
}

@Composable
private fun RowScope.ThemeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Dictee.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(Tokens.BUTTON_HEIGHT_COMPACT_DP.dp)
            .then(if (selected) Modifier.softShadow(SegmentShape, elevation = 2.dp, lightOnly = false) else Modifier)
            .clip(SegmentShape)
            .background(if (selected) colors.surface else Color.Transparent)
            .focusRing(source, Tokens.RADIUS_INNER_DP.dp)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Dictee.type.label,
            color = if (selected) colors.text else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Le journal de l'app, puis la version installée. */
@Composable
private fun AboutSection(onJournal: () -> Unit) {
    Section("À propos") {
        GroupCard {
            ListRow(title = "Journal", onClick = onJournal) { Chevron() }
            RowDivider()
            ListRow(title = "Version", weight = FontWeight.Normal) {
                Text(text = BuildConfig.VERSION_NAME, style = Dictee.type.mono, color = Dictee.colors.textSecondary)
            }
        }
    }
}
