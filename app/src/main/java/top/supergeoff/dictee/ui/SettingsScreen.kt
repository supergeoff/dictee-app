package top.supergeoff.dictee.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import top.supergeoff.dictee.BOX_BACKLIGHT_CHOICES
import top.supergeoff.dictee.BOX_DURATION_CHOICES
import top.supergeoff.dictee.BOX_NOISE_CHOICES
import top.supergeoff.dictee.BoxSetting
import top.supergeoff.dictee.BuildConfig
import top.supergeoff.dictee.DriveClient
import top.supergeoff.dictee.LocalState
import top.supergeoff.dictee.R
import top.supergeoff.dictee.ThemeChoice
import top.supergeoff.dictee.UiState
import top.supergeoff.dictee.SettingsDestination
import top.supergeoff.dictee.backlightChoice
import top.supergeoff.dictee.batteryLabel
import top.supergeoff.dictee.boxSettingsEditable
import top.supergeoff.dictee.boxSettingsStatusText
import top.supergeoff.dictee.noiseReductionChoice
import top.supergeoff.dictee.segmentDurationChoice
import top.supergeoff.dictee.storageLabel

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
    destination: SettingsDestination? = null,
) {
    val storageBringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val transcriptionBringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val synthesisBringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(destination) {
        when (destination) {
            SettingsDestination.STORAGE -> storageBringIntoView.bringIntoView()
            SettingsDestination.TRANSCRIPTION -> transcriptionBringIntoView.bringIntoView()
            SettingsDestination.SYNTHESIS -> synthesisBringIntoView.bringIntoView()
            null -> Unit
        }
    }
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
            BoxSection(ui, model, onAssociate = onAssociate)
            androidx.compose.foundation.layout.Box(Modifier.bringIntoViewRequester(storageBringIntoView)) {
                StorageSection(ui.phoneFolder, busy = ui.recovering || ui.sending, onChange = model::choosePhoneFolder)
            }
            DriveSection(ui.local, model, onDriveFolders)
            androidx.compose.foundation.layout.Box(Modifier.bringIntoViewRequester(transcriptionBringIntoView)) {
                TranscriptionSection(ui.local, model.transcriptionForm, model::setAutoProcessAfterRecovery)
            }
            androidx.compose.foundation.layout.Box(Modifier.bringIntoViewRequester(synthesisBringIntoView)) {
                SynthesisSection(ui.local.synthesisSettings, model.synthesisForm, onPrompt)
            }
            AppearanceSection(ui.local.theme, model::setTheme)
            AboutSection(onJournal)
        }
    }
}

/**
 * Le boîtier associé avec son adresse, puis l'état lu (batterie, stockage) et les trois réglages segmentés.
 * Sans boîtier, le bouton devient « Associer » : les réglages s'ouvrent aussi avant l'association. Chaque
 * réglage s'écrit dès qu'un choix est touché, sans bouton « Enregistrer » ni mise à jour optimiste : l'affiché
 * suit la relecture du boîtier. Hors ligne, sans lecture fraîche ou pendant un travail ([busy]), les choix sont
 * grisés et la mention indique « Dernières valeurs lues » ; sans aucune lecture, elle affiche « Non lu ».
 */
@Composable
private fun BoxSection(ui: UiState, model: AppModel, onAssociate: () -> Unit) {
    val local = ui.local
    val associated = local.deviceAddress != null
    val status = local.boxStatus
    val busy = ui.refreshing || ui.recovering || ui.deletingBox || ui.settingBox
    val editable = boxSettingsEditable(ui.link, ui.boxStatusFresh, busy, hasStatus = status != null)
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
            if (associated && status != null) {
                RowDivider()
                ListRow(title = "Batterie", weight = FontWeight.Normal) {
                    Text(batteryLabel(status.batteryPercent), style = Dictee.type.caption, color = Dictee.colors.textSecondary)
                }
                RowDivider()
                ListRow(title = "Stockage", weight = FontWeight.Normal) {
                    Text(status.storageLabel(), style = Dictee.type.caption, color = Dictee.colors.textSecondary)
                }
                RowDivider()
                BoxSettingSelector("Réduction du bruit", BOX_NOISE_CHOICES, noiseReductionChoice(status.noise), editable) { choice ->
                    model.setBoxSetting(BoxSetting.Noise(choice))
                }
                RowDivider()
                BoxSettingSelector("Durée des segments", BOX_DURATION_CHOICES, segmentDurationChoice(status.segmentDuration), editable) { choice ->
                    model.setBoxSetting(BoxSetting.Duration(choice))
                }
                RowDivider()
                BoxSettingSelector("Rétro-éclairage", BOX_BACKLIGHT_CHOICES, backlightChoice(status.backlight), editable) { choice ->
                    model.setBoxSetting(BoxSetting.Backlight(choice))
                }
            }
        }
        if (associated) {
            boxSettingsStatusText(hasStatus = status != null, fresh = ui.boxStatusFresh)?.let { mention ->
                Text(mention, style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
    }
}

/**
 * Un réglage du boîtier : son titre, la mention « Inconnu » quand la valeur lue n'est pas documentée (aucun
 * choix actif alors), puis un sélecteur segmenté comme celui du thème. Quand [enabled] est faux, la rangée
 * passe à `opacity-inactive` : les valeurs restent visibles mais aucun choix ne répond.
 */
@Composable
private fun <T> BoxSettingSelector(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T?,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    val colors = Dictee.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else Tokens.INACTIVE_ALPHA)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = Dictee.type.rowTitle, color = colors.text, modifier = Modifier.weight(1f))
            if (selected == null) {
                Text(text = "Inconnu", style = Dictee.type.caption, color = colors.textSecondary)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ElementShape)
                .background(colors.muted)
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            choices.forEach { (choice, label) ->
                BoxSettingSegment(label, choice == selected, enabled) { onSelect(choice) }
            }
        }
    }
}

/** Un choix du sélecteur d'un réglage du boîtier : le même segment que les boutons du thème, mu quand [enabled] est faux.
 * Toucher le choix déjà actif ne déclenche rien : seul un nouveau choix écrit le réglage. */
@Composable
private fun RowScope.BoxSettingSegment(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Dictee.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(48.dp)
            .then(if (selected) Modifier.softShadow(SegmentShape, elevation = 2.dp, lightOnly = false) else Modifier)
            .clip(SegmentShape)
            .background(if (selected) colors.surface else Color.Transparent)
            .focusRing(source, Tokens.RADIUS_INNER_DP.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = { if (!selected) onClick() },
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

@Composable
internal fun AutomaticProcessingSetting(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupCard {
            ListRow(
                title = "Créer le VTT et le MD après récupération",
                modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
                weight = FontWeight.Normal,
            ) { Toggle(enabled) }
        }
        Text("Après chaque récupération, crée les fichiers manquants. L’envoi sur Drive reste manuel.",
            style = Dictee.type.caption, color = Dictee.colors.textSecondary)
    }
}

/** Le formulaire reste dans le modèle pendant la sauvegarde et ne préremplit jamais la clé. */
@Composable
internal fun TranscriptionSection(local: LocalState, form: TranscriptionSettingsForm, onAutoProcessChange: (Boolean) -> Unit = {}) {
    LaunchedEffect(form) { form.initialize(local.transcriptionSettings) }
    val draft by form.state.collectAsState()
    val settings = local.transcriptionSettings
    Section("Transcription") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomaticProcessingSetting(local.autoProcessAfterRecovery, onAutoProcessChange)
            if (local.transcriptionAddressNeedsReview) {
                Text("L’ancienne adresse de transcription était invalide. Saisis une adresse de serveur et enregistre les réglages.", color = Dictee.colors.textSecondary)
            }
            OutlinedTextField(draft.baseUrl, form::setBaseUrl, enabled = draft.initialized && !draft.saving, label = { Text("Adresse du serveur (/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Clé", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.secret, form::setSecret, enabled = draft.initialized && !draft.saving, placeholder = if (settings.credentialRef != null) "••••••••" else null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("transcription-secret").semantics { contentDescription = "Clé" })
            Text(if (settings.credentialRef == null) "Aucune clé enregistrée" else "Une clé est enregistrée. Saisis une nouvelle clé pour la remplacer.", color = Dictee.colors.textSecondary)
            OutlinedTextField(draft.language, form::setLanguage, enabled = draft.initialized && !draft.saving, label = { Text("Langue") }, supportingText = { Text("auto : détection automatique (français et anglais).") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
            Text("Adresse du serveur (/v1)", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.baseUrl, form::setBaseUrl, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("synthesis-address").semantics { contentDescription = "Adresse du serveur (/v1)" })
            Text("Clé", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.secret, form::setSecret, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), placeholder = if (settings.credentialRef != null) "••••••••" else null, modifier = Modifier.fillMaxWidth().testTag("synthesis-secret").semantics { contentDescription = "Clé" })
            Text(if (settings.credentialRef == null) "Aucune clé enregistrée. La clé est protégée par Android Keystore." else "Une clé est enregistrée. Saisis une nouvelle clé pour la remplacer.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            Text("Modèle", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.model, form::setModel, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("synthesis-model").semantics { contentDescription = "Modèle" })
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
