package top.supergeoff.dictee.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
 * Réglages du lot 1, une page découpée en rubriques : le boîtier, le stockage sur le téléphone, Google Drive, l'apparence et
 * « À propos ». Les rubriques d'automatisation, de transcription et de synthèse arrivent avec les lots suivants.
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

/** Interrupteur de 44 sur 24 dp, sans réponse propre : c'est la rangée qui bascule. Allumé, il prend `primary` ; éteint, l'aplat gris du bouton secondaire. */
@Composable
private fun Toggle(checked: Boolean) {
    val colors = Dictee.colors
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(if (checked) colors.primary else colors.secondary)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(if (checked) colors.onPrimary else colors.textSecondary))
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
        Text(text = text, style = Dictee.type.captionStrong, color = Dictee.colors.textSecondary, maxLines = 1)
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
