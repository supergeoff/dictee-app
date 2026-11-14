package top.supergeoff.dictee.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.supergeoff.dictee.VttCue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
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
import top.supergeoff.dictee.RequestedWork
import top.supergeoff.dictee.SynthesisSelection
import top.supergeoff.dictee.SynthesisPhase
import top.supergeoff.dictee.ProvenanceStatus
import top.supergeoff.dictee.SynthesisDocument
import top.supergeoff.dictee.TranscriptionPhase
import top.supergeoff.dictee.TranscriptionTask
import top.supergeoff.dictee.UiState
import top.supergeoff.dictee.isTerminal
import top.supergeoff.dictee.transcriptionCancellationMessage
import top.supergeoff.dictee.synthesisDocumentRequest
import top.supergeoff.dictee.synthesisProgressMessage

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
        val task = ui.local.transcriptionTasks.lastOrNull { it.recordingName == name && it.synthesis != null }
        val activeTask = ui.local.transcriptionTasks.firstOrNull { it.recordingName == name && !it.phase.isTerminal() }
        val cancellable = activeTask != null
        val activeNames = ui.local.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
        fun canRequest(request: RequestedWork) = SynthesisSelection.plan(listOf(recording), setOf(name), ui.local.errors, activeNames, request).first.isNotEmpty()
        val canRedoVtt = canRequest(RequestedWork.REDO_VTT_AND_MD)
        val canRedoMd = canRequest(RequestedWork.REDO_MD)
        var confirmCancel by remember { mutableStateOf(false) }
        if (confirmCancel) {
            DicteeDialog(
                title = if (activeTask?.phase == TranscriptionPhase.SYNTHESIZING) "Annuler la synthèse ?" else "Annuler la transcription ?",
                confirm = if (activeTask?.phase == TranscriptionPhase.SYNTHESIZING) "Annuler la synthèse" else "Annuler la transcription",
                destructive = true,
                onConfirm = { confirmCancel = false; model.cancelTranscription(name) },
                onDismiss = { confirmCancel = false },
            ) {
                Text(
                    text = transcriptionCancellationMessage(activeTask),
                    style = Dictee.type.caption, color = Dictee.colors.textSecondary,
                )
            }
        }
        RecordingBar(recording, canRecover, onBack, onRecover = { model.recover(listOf(name)) },
            canCancelTranscription = cancellable, cancellingSynthesis = activeTask?.phase == TranscriptionPhase.SYNTHESIZING,
            onCancelTranscription = { confirmCancel = true }, canRedoVtt = canRedoVtt, canRedoMd = canRedoMd,
            onRedoVtt = { model.transcribe(setOf(name), RequestedWork.REDO_VTT_AND_MD) },
            onRedoMd = { model.transcribe(setOf(name), RequestedWork.REDO_MD) })
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (recording.state as? DisplayState.Error)?.let { ErrorBanner(it.message, onRetry = { model.retry(name) }) }
            SynthesisTaskNotice(task, ui.local.transcriptionTasks) { model.retrySynthesis(it) }
            val mp3 = recording.phoneFiles[FileKind.MP3]
            val playback = if (mp3 == null) null else rememberPlayback(model, mp3.fileName, mp3.size)
            if (mp3 == null) MissingMp3() else {
                Player(playback!!)
                FileLine(mp3.fileName, ui.phoneFolder)
            }
            val md = recording.phoneFiles[FileKind.MD]
            val vtt = recording.phoneFiles[FileKind.VTT]
            val document by produceState<Result<SynthesisDocument>?>(null, md, vtt, ui.local.synthesisProvenance, ui.local.transcriptionTasks, ui.refreshing, ui.phoneRevision) {
                value = null
                value = if (md == null) null else withContext(Dispatchers.IO) { model.readSynthesis(md.fileName, vtt?.fileName) }
            }
            val current = document?.getOrNull()
            val label = current?.let {
                when (it.status) {
                    ProvenanceStatus.UNKNOWN -> "Modèle et provenance inconnus."
                    ProvenanceStatus.STALE -> "Cette synthèse utilise l'ancien VTT. Modèle utilisé : ${it.model}"
                    ProvenanceStatus.CURRENT -> "Modèle utilisé : ${it.model}"
                }
            }
            if (current?.status == ProvenanceStatus.STALE) {
                Notice("Cette synthèse utilise l'ancien VTT. Le MD précédent reste lisible jusqu'à son remplacement.")
            }
            val documentRequest = synthesisDocumentRequest(mdPresent = md != null)
            RecordingDocuments(markdown = current?.markdown, provenanceLabel = label, canCreate = vtt != null && canRequest(documentRequest),
                onCreate = { model.transcribe(setOf(name), documentRequest) }, mdPresent = md != null, mdError = document?.isFailure == true,
                hasVtt = vtt != null, stale = current?.status == ProvenanceStatus.STALE) {
                if (vtt == null) Notice("La transcription n'est pas encore disponible.") else Transcript(model, vtt, playback, ui.phoneRevision)
            }
        }
    }
}

@Composable
internal fun SynthesisTaskNotice(task: TranscriptionTask?, tasks: List<TranscriptionTask>, onRetry: (String) -> Unit) {
    val work = task?.synthesis ?: return
    if (work.phase == SynthesisPhase.SUSPENDED) {
        val resumable = task.phase == TranscriptionPhase.SYNTHESIZING && work.context != null &&
            (work.stagedSha256 != null || (!work.context.vttSha256.isNullOrBlank() && !work.context.systemPrompt.isNullOrBlank()))
        Notice(work.suspensionReason ?: "La synthèse est suspendue.", NoticeTone.Destructive,
            action = if (resumable) ({ CompactButton("Relancer le MD") { onRetry(task.localId) } }) else null)
    } else {
        synthesisProgressMessage(task, tasks)?.let { Notice(it) }
    }
}

@Composable
internal fun RecordingDocuments(
    markdown: String?, provenanceLabel: String?, canCreate: Boolean, onCreate: () -> Unit,
    mdPresent: Boolean = markdown != null, mdError: Boolean = false, hasVtt: Boolean = canCreate, stale: Boolean = false,
    transcript: @Composable () -> Unit,
) {
    var synthesis by remember { mutableStateOf(true) }
    SecondaryTabRow(selectedTabIndex = if (synthesis) 0 else 1, containerColor = Dictee.colors.background, contentColor = Dictee.colors.text) {
        Tab(selected = synthesis, onClick = { synthesis = true }, text = { Text("Synthèse", style = Dictee.type.control) })
        Tab(selected = !synthesis, onClick = { synthesis = false }, text = { Text("Transcription", style = Dictee.type.control) })
    }
    if (!synthesis) transcript() else Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            markdown != null -> {
                MarkdownView(markdown, Modifier.fillMaxWidth())
                if (provenanceLabel != null) Text(provenanceLabel, style = Dictee.type.caption, color = Dictee.colors.textSecondary)
                if (stale && canCreate) {
                    CompactButton("Relancer le MD", onClick = onCreate)
                }
            }
            mdError -> Text("Le fichier MD n'est pas lisible.", style = Dictee.type.body, color = Dictee.colors.destructive)
            mdPresent -> Text("Lecture…", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            else -> {
                Text("La synthèse reste à créer", style = Dictee.type.rowTitle, color = Dictee.colors.text)
                Text(if (hasVtt) "Le VTT présent sera utilisé sans retranscrire l'audio." else "La synthèse pourra être créée lorsque le VTT sera disponible.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
                if (canCreate) PrimaryButton("Créer le MD", onClick = onCreate)
            }
        }
    }
}

/** En-tête : le retour, le titre, la durée avec l'état, puis le menu. */
@Composable
private fun RecordingBar(
    recording: Recording, canRecover: Boolean, onBack: () -> Unit, onRecover: () -> Unit,
    canCancelTranscription: Boolean, cancellingSynthesis: Boolean, onCancelTranscription: () -> Unit,
    canRedoVtt: Boolean, canRedoMd: Boolean, onRedoVtt: () -> Unit, onRedoMd: () -> Unit,
) {
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
                style = Dictee.type.titleItem,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = duration(recording), style = Dictee.type.caption, color = colors.textSecondary, maxLines = 1)
                StateBadge(recording.state)
            }
        }
        RecordingMenu(canRecover, onRecover, canCancelTranscription, cancellingSynthesis, onCancelTranscription, canRedoVtt, canRedoMd, onRedoVtt, onRedoMd)
    }
}

/** La durée estimée depuis la taille, par exemple « 41 min ». */
private fun duration(recording: Recording): String =
    RecordName.formatDuration(RecordName.durationSeconds(recording.size))

/** Menu de la fiche : « Récupérer à nouveau », grisé sans liaison avec le boîtier ou pendant une récupération. */
@Composable
internal fun RecordingMenu(canRecover: Boolean, onRecover: () -> Unit, canCancelTranscription: Boolean, cancellingSynthesis: Boolean,
    onCancelTranscription: () -> Unit, canRedoVtt: Boolean, canRedoMd: Boolean, onRedoVtt: () -> Unit, onRedoMd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(R.drawable.ic_more, "Plus d'actions") { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = ElementShape,
            containerColor = Dictee.colors.surface,
            border = BorderStroke(1.dp, Dictee.colors.border),
        ) {
            DropdownMenuItem(text = { Text("Refaire le VTT et le MD", style = Dictee.type.rowTitle) }, enabled = canRedoVtt,
                onClick = { open = false; onRedoVtt() })
            DropdownMenuItem(text = { Text("Refaire le MD", style = Dictee.type.rowTitle) }, enabled = canRedoMd,
                onClick = { open = false; onRedoMd() })
            DropdownMenuItem(
                text = { Text(text = "Récupérer à nouveau", style = Dictee.type.rowTitle) },
                enabled = canRecover,
                onClick = {
                    open = false
                    onRecover()
                },
            )
            if (canCancelTranscription) DropdownMenuItem(
                text = { Text(text = if (cancellingSynthesis) "Annuler la synthèse" else "Annuler la transcription", style = Dictee.type.rowTitle) },
                onClick = {
                    open = false
                    onCancelTranscription()
                },
            )
        }
    }
}

/** Encart d'erreur sous l'en-tête : la raison en clair et « Réessayer », un bouton compact gris, lisible sur la teinte. */
@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Notice(text = message, tone = NoticeTone.Destructive) { CompactButton(text = "Réessayer", onClick = onRetry) }
}

/** Carte à la place du lecteur quand le MP3 n'est pas dans le dossier du téléphone. */
@Composable
private fun MissingMp3() {
    Box(modifier = Modifier.fillMaxWidth().card().padding(16.dp)) {
        Text(text = "Le MP3 n'est pas encore sur le téléphone.", style = Dictee.type.body, color = Dictee.colors.textSecondary)
    }
}

/** Segments du VTT : un toucher place la lecture du MP3 au début du segment et la lance. Un fichier illisible n'affecte que cette carte. */
@Composable
private fun Transcript(model: AppModel, vtt: PhoneFile, playback: Playback?, phoneRevision: Long) {
    val colors = Dictee.colors
    val cues by produceState<Result<List<VttCue>>?>(null, vtt.fileName, vtt.size, phoneRevision) {
        value = withContext(Dispatchers.IO) { model.readTranscript(vtt.fileName) }
    }
    Column(modifier = Modifier.fillMaxWidth().card().padding(vertical = 4.dp)) {
        Text(text = "Transcription", style = Dictee.type.rowTitle, color = colors.text, modifier = Modifier.padding(12.dp))
        val result = cues
        when {
            result == null -> Text("Lecture…", style = Dictee.type.caption, color = colors.textSecondary, modifier = Modifier.padding(12.dp))
            result.isFailure -> Text("Le fichier VTT n'est pas lisible.", style = Dictee.type.caption, color = colors.destructive, modifier = Modifier.padding(12.dp))
            result.getOrThrow().isEmpty() -> Text("Aucune parole détectée.", style = Dictee.type.caption, color = colors.textSecondary, modifier = Modifier.padding(12.dp))
            else -> result.getOrThrow().forEach { cue ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .pressable(radius = 0.dp, enabled = playback != null && playback.status != PlaybackStatus.FAILED, onClick = { playback?.playFrom(cue.startMs) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = formatClock(cue.startMs.toInt()) + (cue.speaker?.let { " · $it" } ?: ""),
                        style = Dictee.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp), color = colors.textSecondary,
                    )
                    Text(text = cue.text, style = Dictee.type.body, color = colors.text)
                }
            }
        }
    }
}

/** Lecteur du MP3 : le bouton lecture ou pause, la barre de position, puis la position et la durée. */
@Composable
private fun Player(playback: Playback) {
    // Pendant un glissement, la barre et le temps suivent le doigt ; la lecture ne saute qu'à la fin du geste.
    var drag by remember { mutableStateOf<Float?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().card().padding(12.dp),
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
            .alpha(if (enabled) 1f else Tokens.DISABLED_ALPHA)
            .clip(CircleShape)
            .background(colors.primary)
            .pressable(radius = Tokens.RADIUS_PILL_DP.dp, enabled = enabled, onClick = playback::toggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (playback.playing) "Pause" else "Lecture",
            modifier = Modifier.size(16.dp),
            tint = colors.onPrimary,
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
        Text(text = "Lecture impossible.", style = Dictee.type.caption, color = colors.destructive)
        return
    }
    val mono = Dictee.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp)
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
        modifier = Modifier.fillMaxWidth().card().padding(horizontal = 12.dp, vertical = 10.dp),
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
                Text(text = folder, style = Dictee.type.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RowIcon(R.drawable.ic_check, size = 16.dp)
    }
}
