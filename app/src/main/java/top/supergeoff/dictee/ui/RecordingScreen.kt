package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.DisplayState
import top.supergeoff.dictee.FileKind
import top.supergeoff.dictee.LinkStatus
import top.supergeoff.dictee.PhoneFile
import top.supergeoff.dictee.R
import top.supergeoff.dictee.RecordName
import top.supergeoff.dictee.Recording
import top.supergeoff.dictee.UiState

/**
 * Fiche d'un enregistrement : titre, durée et état dans l'en-tête, le lecteur du MP3 du téléphone, puis le fichier et son dossier.
 * Une erreur d'étape s'affiche sous l'en-tête avec « Réessayer ». Sans MP3 sur le téléphone, un message remplace le lecteur.
 * Si l'enregistrement quitte la liste (fichier retiré du dossier), la fiche se ferme : elle n'aurait plus rien à montrer.
 */
@Composable
fun RecordingScreen(ui: UiState, name: String, model: AppModel, onBack: () -> Unit) {
    val recording = ui.recordings.firstOrNull { it.name == name }
    if (recording == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val canRecover = ui.local.boxList.any { it.name == name } && ui.link == LinkStatus.CONNECTED && !ui.recovering
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        RecordingBar(recording, canRecover, onBack, onRecover = { model.recover(listOf(name)) })
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (recording.state as? DisplayState.Error)?.let { ErrorBanner(it.message, onRetry = { model.retry(name) }) }
            val mp3 = recording.phoneFiles[FileKind.MP3]
            if (mp3 == null) {
                MissingMp3()
            } else {
                Player(model, mp3)
                FileLine(mp3.fileName, ui.phoneFolder)
            }
        }
    }
}

/** En-tête : le retour, le titre, la durée avec l'état, puis le menu. */
@Composable
private fun RecordingBar(recording: Recording, canRecover: Boolean, onBack: () -> Unit, onRecover: () -> Unit) {
    val colors = Dictee.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconAction(R.drawable.ic_back, "Retour", onBack)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = RecordName.detailTitle(recording.type, recording.name),
                style = sized(16, 20, FontWeight.SemiBold),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = duration(recording), style = sized(13, 18), color = colors.textSecondary, maxLines = 1)
                StateBadge(recording.state)
            }
        }
        RecordingMenu(canRecover, onRecover)
    }
}

/** La durée estimée depuis la taille, par exemple « 41 min ». */
private fun duration(recording: Recording): String =
    RecordName.formatDuration(RecordName.durationSeconds(recording.size))

/** Menu de la fiche : « Récupérer à nouveau », grisé sans liaison avec le boîtier ou pendant une récupération. */
@Composable
private fun RecordingMenu(canRecover: Boolean, onRecover: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more, "Plus d'actions") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(text = "Récupérer à nouveau", style = sized(15, 20, FontWeight.Medium)) },
                enabled = canRecover,
                onClick = {
                    open = false
                    onRecover()
                },
            )
        }
    }
}

/** Bandeau d'erreur sous l'en-tête : la raison en clair et « Réessayer ». */
@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    val colors = Dictee.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(colors.errorBackground)
            .border(1.dp, colors.errorText.copy(alpha = 0.25f), ControlShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.errorText)
        Text(text = message, style = sized(12, 17), color = colors.errorText, modifier = Modifier.weight(1f))
        RetryButton(onRetry)
    }
}

@Composable
private fun RetryButton(onClick: () -> Unit) {
    val colors = Dictee.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .border(1.dp, colors.errorText, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Réessayer", style = sized(12, 16, FontWeight.SemiBold), color = colors.errorText, maxLines = 1)
    }
}

/** Carte à la place du lecteur quand le MP3 n'est pas dans le dossier du téléphone. */
@Composable
private fun MissingMp3() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Dictee.colors.surface)
            .border(1.dp, Dictee.colors.border, CardShape)
            .padding(16.dp),
    ) {
        Text(text = "Le MP3 n'est pas encore sur le téléphone.", style = sized(14, 21), color = Dictee.colors.textSecondary)
    }
}

/** Lecteur du MP3 : le bouton lecture ou pause, la barre de position, puis la position et la durée. */
@Composable
private fun Player(model: AppModel, mp3: PhoneFile) {
    val playback = rememberPlayback(model, mp3.fileName, mp3.size)
    // Pendant un glissement, la barre et le temps suivent le doigt ; la lecture ne saute qu'à la fin du geste.
    var drag by remember { mutableStateOf<Float?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Dictee.colors.surface)
            .border(1.dp, Dictee.colors.border, CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayButton(playback)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeekBar(playback, drag, onDrag = { drag = it })
            PlayerTimes(playback, ((drag ?: playback.fraction) * playback.durationMs).toInt())
        }
    }
}

@Composable
private fun PlayButton(playback: Playback) {
    val colors = Dictee.colors
    val enabled = playback.status == PlaybackStatus.READY
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.primary else colors.border)
            .clickable(enabled = enabled, role = Role.Button, onClick = playback::toggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (playback.playing) "Pause" else "Lecture",
            modifier = Modifier.size(16.dp),
            tint = if (enabled) colors.onPrimary else colors.textSecondary,
        )
    }
}

/**
 * Barre de position de 4 dp avec sa pastille de 12 dp, dans une zone de 24 dp de haut pour le doigt. Un toucher place la lecture,
 * un glissement la place au relâchement ; [drag] est la position du doigt pendant le glissement, `null` sinon.
 */
@Composable
private fun SeekBar(playback: Playback, drag: Float?, onDrag: (Float?) -> Unit) {
    val colors = Dictee.colors
    val enabled = playback.status == PlaybackStatus.READY
    val fraction = drag ?: playback.fraction
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .seekGestures(playback, enabled, onDrag)
            .semantics {
                contentDescription = "Position de lecture"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress {
                        playback.seekTo(it)
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.border)) {
            Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
        }
        Box(modifier = Modifier.offset(x = maxWidth * fraction - 6.dp).size(12.dp).clip(CircleShape).background(colors.primary))
    }
}

/** Toucher et glissement de la barre de position ; sans effet tant que le lecteur n'est pas prêt. */
private fun Modifier.seekGestures(playback: Playback, enabled: Boolean, onDrag: (Float?) -> Unit): Modifier = this
    .pointerInput(playback, enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures { playback.seekTo((it.x / size.width).coerceIn(0f, 1f)) }
    }
    .pointerInput(playback, enabled) {
        if (!enabled) return@pointerInput
        // Dernière position du doigt, tenue ici et non dans l'état du composable : un relâchement dans la même image que le dernier
        // mouvement arrive avant la recomposition, et lirait sinon la position précédente.
        var last: Float? = null
        fun track(x: Float) {
            last = (x / size.width).coerceIn(0f, 1f)
            onDrag(last)
        }
        detectHorizontalDragGestures(
            onDragStart = { track(it.x) },
            onDragEnd = {
                last?.let(playback::seekTo)
                last = null
                onDrag(null)
            },
            onDragCancel = {
                last = null
                onDrag(null)
            },
            onHorizontalDrag = { change, _ -> track(change.position.x) },
        )
    }

/** Position à gauche, durée à droite en JetBrains Mono ; un échec de lecture remplace les deux par son message. */
@Composable
private fun PlayerTimes(playback: Playback, positionMs: Int) {
    val colors = Dictee.colors
    if (playback.status == PlaybackStatus.FAILED) {
        Text(text = "Lecture impossible.", style = sized(12, 16), color = colors.errorText)
        return
    }
    val mono = Dictee.type.mono.copy(fontSize = 11.sp, lineHeight = 14.sp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = formatClock(positionMs), style = mono, color = colors.textSecondary)
        Text(text = formatClock(playback.durationMs), style = mono, color = colors.textSecondary)
    }
}

/** Le nom du MP3 en JetBrains Mono et le chemin du dossier du téléphone, avec la coche du fichier présent. */
@Composable
private fun FileLine(fileName: String, folder: String?) {
    val colors = Dictee.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surface)
            .border(1.dp, colors.border, CardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowIcon(R.drawable.ic_file)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = Dictee.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder != null) {
                Text(text = folder, style = sized(12, 16), color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RowIcon(R.drawable.ic_check, size = 16.dp)
    }
}
