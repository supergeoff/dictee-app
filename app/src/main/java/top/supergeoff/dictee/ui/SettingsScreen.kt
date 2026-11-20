package top.supergeoff.dictee.ui


import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import top.supergeoff.dictee.transcriptionTaskSettingsMessages
import top.supergeoff.dictee.SpeakerName
import top.supergeoff.dictee.SpeakerNames
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

import top.supergeoff.dictee.backlightChoice
import top.supergeoff.dictee.batteryLabel
import top.supergeoff.dictee.boxSettingsEditable
import top.supergeoff.dictee.boxSettingsStatusText
import top.supergeoff.dictee.noiseReductionChoice
import top.supergeoff.dictee.segmentDurationChoice
import top.supergeoff.dictee.storageLabel

/** Une rubrique par écran, sans formulaire imbriqué sur la page principale. */
enum class SettingsPage(val title: String) {
    HOME("Réglages"), BOX("Boîtier"), STORAGE("Stockage et Google Drive"), TRANSCRIPTION("Transcription"), SYNTHESIS("Synthèse")
}

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
    page: SettingsPage = SettingsPage.HOME,
    onPage: (SettingsPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        TopBar(page.title, onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (page) {
                SettingsPage.HOME -> SettingsHomeSections(ui.local.theme, model::setTheme, onJournal, onPage)
                SettingsPage.BOX -> BoxSection(ui, model, onAssociate = onAssociate)
                SettingsPage.STORAGE -> {
                    StorageSection(ui.phoneFolder, busy = ui.recovering || ui.sending, onChange = model::choosePhoneFolder)
                    DriveSection(ui.local, model, onDriveFolders)
                }
                SettingsPage.TRANSCRIPTION -> TranscriptionSection(
                    ui.local, model.transcriptionForm, model::setAutoProcessAfterRecovery, model::renameSpeakerName, model::removeSpeakerName,
                )
                SettingsPage.SYNTHESIS -> SynthesisSection(ui.local.synthesisSettings, model.synthesisForm, onPrompt)
            }
        }
    }
}

@Composable
internal fun SettingsHomeSections(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, onJournal: () -> Unit, onPage: (SettingsPage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        GroupCard {
            val pages = listOf(SettingsPage.BOX, SettingsPage.STORAGE, SettingsPage.TRANSCRIPTION, SettingsPage.SYNTHESIS)
            pages.forEachIndexed { index, page ->
                if (index > 0) RowDivider()
                ListRow(title = page.title, onClick = { onPage(page) }) { Chevron() }
            }
        }
        AppearanceSection(theme, onTheme)
        AboutSection(onJournal)
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
    val busy = ui.refreshing || ui.recovering || ui.deleting || ui.settingBox
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
internal fun TranscriptionSection(
    local: LocalState, form: TranscriptionSettingsForm, onAutoProcessChange: (Boolean) -> Unit = {},
    onRenameSpeaker: (String, String) -> Unit = { _, _ -> }, onRemoveSpeaker: (String) -> Unit = {},
) {
    LaunchedEffect(form) { form.initialize(local.transcriptionSettings) }
    val draft by form.state.collectAsState()
    val settings = local.transcriptionSettings
    Section("Transcription") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutomaticProcessingSetting(local.autoProcessAfterRecovery, onAutoProcessChange)
            if (local.transcriptionAddressNeedsReview) {
                Text("L’ancienne adresse de transcription était invalide. Saisis une adresse de serveur et enregistre les réglages.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
            Text("Adresse du serveur (/v1)", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.baseUrl, form::setBaseUrl, enabled = draft.initialized && !draft.saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("transcription-address").semantics { contentDescription = "Adresse du serveur (/v1)" })
            Text("Clé", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.secret, form::setSecret, enabled = draft.initialized && !draft.saving, placeholder = if (settings.credentialRef != null) "••••••••" else null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("transcription-secret").semantics { contentDescription = "Clé" })
            Text(if (settings.credentialRef == null) "Aucune clé enregistrée" else "Une clé est enregistrée. Saisis une nouvelle clé pour la remplacer.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            Text("Langue", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.language, form::setLanguage, enabled = draft.initialized && !draft.saving, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Langue" })
            Text("auto : détection automatique (français et anglais).", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            Text("Modèle", style = Dictee.type.label, color = Dictee.colors.text)
            DicteeTextField(draft.model, form::setModel, enabled = draft.initialized && !draft.saving, modifier = Modifier.fillMaxWidth().testTag("transcription-model").semantics { contentDescription = "Modèle" })
            GlossaryEditor(form)
            SpeakerBook(local.speakerNames, onRenameSpeaker, onRemoveSpeaker)
            PrimaryButton(if (draft.saving) "Enregistrement…" else "Enregistrer", enabled = draft.initialized && !draft.saving, modifier = Modifier.fillMaxWidth().testTag("transcription-save")) { form.submit() }
            draft.message?.let { Text(it, style = Dictee.type.bodySm, color = Dictee.colors.text) }
            transcriptionTaskSettingsMessages(local.transcriptionTasks).forEach { message ->
                Text(message, style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
            Text("Le test de connexion sera disponible ultérieurement.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}

/**
 * Le carnet des noms de locuteurs, du plus récent au moins récent. « Renommer » ouvre [SpeakerNameDialog] ; « Supprimer » demande
 * confirmation. Retirer un nom du carnet ne change aucun VTT ni MD déjà nommé.
 */
@Composable
private fun SpeakerBook(book: List<SpeakerName>, onRename: (String, String) -> Unit, onRemove: (String) -> Unit) {
    var renaming by remember { mutableStateOf<SpeakerName?>(null) }
    var removing by remember { mutableStateOf<SpeakerName?>(null) }
    val sorted = book.sortedByDescending { it.lastUsedAt }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Locuteurs", style = Dictee.type.label, color = Dictee.colors.text)
        if (sorted.isEmpty()) {
            Text("Aucun nom enregistré. Nomme un locuteur depuis l'onglet Transcription d'un enregistrement.",
                style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        } else {
            GroupCard {
                sorted.forEachIndexed { index, speaker ->
                    if (index > 0) RowDivider()
                    ListRow(title = speaker.name, weight = FontWeight.Normal) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextAction("Renommer") { renaming = speaker }
                            TextAction("Supprimer") { removing = speaker }
                        }
                    }
                }
            }
        }
    }
    renaming?.let { speaker ->
        SpeakerNameDialog(speaker.name, onConfirm = { to -> renaming = null; onRename(speaker.name, to) }, onDismiss = { renaming = null })
    }
    removing?.let { speaker ->
        DicteeDialog(
            title = "Supprimer « ${speaker.name} » ?", confirm = "Supprimer", destructive = true,
            onConfirm = { removing = null; onRemove(speaker.name) }, onDismiss = { removing = null },
        ) {
            Text("Le nom disparaît du carnet. Les VTT et MD déjà nommés ne changent pas.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
        }
    }
}

/** Dialogue « Renommer » : le champ part du nom actuel. « Valider » reste désactivé tant que la saisie n'est pas un nom valide. */
@Composable
private fun SpeakerNameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(current) }
    val valid = SpeakerNames.validate(query)
    DicteeDialog(
        title = "Renommer le locuteur", confirm = "Valider", confirmEnabled = valid != null,
        onConfirm = { valid?.let(onConfirm) }, onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DicteeTextField(
                query, { query = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { valid?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nouveau nom du locuteur" },
            )
            Text("40 caractères au plus, sans « < », « > », « & » ni saut de ligne.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
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
