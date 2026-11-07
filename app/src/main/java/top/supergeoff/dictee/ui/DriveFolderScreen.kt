package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.DriveClient
import top.supergeoff.dictee.FolderOrigin
import top.supergeoff.dictee.LocalState
import top.supergeoff.dictee.R
import top.supergeoff.dictee.UiState

/** Une ligne de la liste des dossiers connus, avec ce qu'un toucher en fait. */
private class FolderLine(
    val name: String,
    val subtitle: String,
    val selected: Boolean,
    val accessible: Boolean,
    val onClick: () -> Unit,
)

/**
 * Choix du dossier Drive. À l'ouverture, [AppModel.folderAccess] marque les dossiers connus qui ne répondent plus : ils sont grisés
 * avec « Inaccessible », et muets. Tant que la réponse n'est pas là, tous sont présentés comme accessibles. Choisir un dossier, ou
 * « Dictée », revient aux Réglages ; « Nouveau dossier » et « Autre dossier… » restent ici, où le dossier ajouté apparaît dans la liste.
 */
@Composable
fun DriveFolderScreen(ui: UiState, model: AppModel, onBack: () -> Unit) {
    val access by produceState<Map<String, Boolean>?>(null, ui.local.knownFolders) { value = model.folderAccess() }
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        TopBar("Dossier Drive", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "L'app ne voit que les dossiers qu'elle a créés et ceux que tu lui as ouverts une fois.",
                style = Dictee.type.caption,
                color = Dictee.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            KnownFolders(
                lines = folderLines(
                    local = ui.local,
                    access = access,
                    onChoose = { id ->
                        model.chooseFolder(id)
                        onBack()
                    },
                    onDefault = {
                        model.useDefaultFolder()
                        onBack()
                    },
                ),
            )
            Actions(onNew = { creating = true }, onOther = model::openPicker)
        }
    }
    if (creating) {
        NewFolderDialog(
            onCreate = {
                model.createFolder(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

/**
 * Les lignes de la liste : « Dictée » d'abord s'il n'est pas parmi les dossiers connus, puis les dossiers connus. Le dossier de dépôt porte
 * la coche : le dossier choisi, ou « Dictée » tant qu'aucun n'est choisi.
 */
private fun folderLines(local: LocalState, access: Map<String, Boolean>?, onChoose: (String) -> Unit, onDefault: () -> Unit): List<FolderLine> {
    val chosen = local.chosenFolderId
    val defaultKnown = local.knownFolders.any { it.name == DriveClient.DEFAULT_FOLDER }
    val known = local.knownFolders.map { folder ->
        val accessible = access?.get(folder.id) ?: true
        FolderLine(
            name = folder.name,
            subtitle = when {
                !accessible -> "Inaccessible"
                folder.name == DriveClient.DEFAULT_FOLDER -> "Dossier par défaut"
                folder.origin == FolderOrigin.CREATED -> "Créé par l'app"
                else -> "Ouvert avec le sélecteur Google"
            },
            selected = if (chosen == null) folder.name == DriveClient.DEFAULT_FOLDER else folder.id == chosen,
            accessible = accessible,
            onClick = { onChoose(folder.id) },
        )
    }
    if (defaultKnown) return known
    val absent = FolderLine(
        name = DriveClient.DEFAULT_FOLDER,
        subtitle = "Dossier par défaut, créé au premier envoi",
        selected = chosen == null,
        accessible = true,
        onClick = onDefault,
    )
    return listOf(absent) + known
}

@Composable
private fun KnownFolders(lines: List<FolderLine>) {
    Section("Dossiers connus") {
        GroupCard {
            lines.forEachIndexed { index, line ->
                if (index > 0) RowDivider()
                ListRow(
                    title = line.name,
                    subtitle = line.subtitle,
                    weight = if (line.selected) FontWeight.SemiBold else FontWeight.Medium,
                    tile = { IconTile(R.drawable.ic_folder) },
                    enabled = line.accessible,
                    onClick = line.onClick,
                ) {
                    if (line.selected) RowIcon(R.drawable.ic_check, size = 20.dp, tint = Dictee.colors.text)
                }
            }
        }
    }
}

@Composable
private fun Actions(onNew: () -> Unit, onOther: () -> Unit) {
    GroupCard {
        ListRow(title = "Nouveau dossier", tile = { DashedTile(R.drawable.ic_add) }, onClick = onNew)
        RowDivider()
        ListRow(
            title = "Autre dossier…",
            subtitle = "Ouvre le sélecteur Google",
            tile = { IconTile(R.drawable.ic_search) },
            onClick = onOther,
        ) {
            RowIcon(R.drawable.ic_open, size = 16.dp)
        }
    }
}

/** Tuile de 36 dp à contour en tirets, pour ce qui ajoute un dossier plutôt que d'en montrer un. */
@Composable
private fun DashedTile(icon: Int) {
    val color = Dictee.colors.textSecondary.copy(alpha = 0.6f)
    Box(
        modifier = Modifier.size(36.dp).drawBehind {
            val stroke = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            inset(0.5.dp.toPx()) { drawRoundRect(color, cornerRadius = CornerRadius(Tokens.RADIUS_DP.dp.toPx()), style = stroke) }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = Dictee.colors.text)
    }
}

/** Dialogue du nom du nouveau dossier : le clavier s'ouvre sur le champ, « Créer » attend un nom. */
@Composable
private fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Le champ n'est lié au focus qu'après la première composition : une demande plus tôt lèverait une exception.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    DicteeDialog(
        title = "Nom du dossier",
        confirm = "Créer",
        onConfirm = { onCreate(name) },
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank(),
    ) {
        DicteeTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name) }),
        )
    }
}
