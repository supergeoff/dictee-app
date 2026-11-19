package top.supergeoff.dictee.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextDecoration
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
import top.supergeoff.dictee.SpeakerName
import top.supergeoff.dictee.SynthesisSelection
import top.supergeoff.dictee.speakerRenameUnavailableReason
import top.supergeoff.dictee.ProvenanceStatus
import top.supergeoff.dictee.SynthesisDocument
import top.supergeoff.dictee.TranscriptionPhase
import top.supergeoff.dictee.UiState
import top.supergeoff.dictee.isTerminal
import top.supergeoff.dictee.transcriptionCancellationMessage
import top.supergeoff.dictee.PipelineStage
import top.supergeoff.dictee.PipelineStatus
import top.supergeoff.dictee.PipelineAction
import top.supergeoff.dictee.PipelineInput
import top.supergeoff.dictee.RecordingPipeline
import top.supergeoff.dictee.isValidSynthesisBaseUrl
import top.supergeoff.dictee.SettingsDestination
import top.supergeoff.dictee.settingsDestination
import top.supergeoff.dictee.recordingForDetail

/**
 * Fiche d'un enregistrement : parcours et action principale, lecteur du MP3, puis documents en lecture seule.
 * Sans MP3 sur le téléphone, un message remplace le lecteur.
 * Si l'enregistrement quitte la liste (fichier retiré du dossier), la fiche se ferme : elle n'aurait plus rien à montrer.
 */
@Composable
fun RecordingScreen(ui: UiState, name: String, model: AppModel, onBack: () -> Unit, onSettings: (SettingsDestination) -> Unit) {
    val recording = recordingForDetail(ui.recordings, name)
    if (recording == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val mp3 = recording.phoneFiles[FileKind.MP3]
    val playback = if (mp3 == null || ui.deletingPhone) null else key(ui.phoneRevision) { rememberPlayback(model, mp3.fileName, mp3.size) }
    ui.phoneDeletionRequest?.let { plan ->
        PhoneDeletionDialog(plan, ui.deletingPhone, onConfirm = {
            playback?.release()
            model.confirmPhoneDeletion()
        }, onDismiss = model::cancelPhoneDeletion)
    }
    ui.boxDeletionRequest?.let { plan ->
        BoxDeletionDialog(plan, ui.deletingBox, model::confirmBoxDeletion, model::cancelBoxDeletion)
    }
    val canRecover = ui.local.boxList.any { it.name == name } && ui.link == LinkStatus.CONNECTED && !ui.recovering
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        val task = ui.local.transcriptionTasks.lastOrNull { it.recordingName == name && it.synthesis != null }
        val activeTask = ui.local.transcriptionTasks.firstOrNull { it.recordingName == name && !it.phase.isTerminal() }
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
        val md = recording.phoneFiles[FileKind.MD]
        val vtt = recording.phoneFiles[FileKind.VTT]
        val document by produceState<Result<SynthesisDocument>?>(null, md, vtt, ui.local.synthesisProvenance, ui.local.transcriptionTasks, ui.refreshing, ui.phoneRevision) {
            value = null
            value = if (md == null) null else withContext(Dispatchers.IO) { model.readSynthesis(md.fileName, vtt?.fileName) }
        }
        val current = document?.getOrNull()
        val settings = ui.local.synthesisSettings
        val configured = settings.baseUrl != "/v1" && isValidSynthesisBaseUrl(settings.baseUrl) &&
            settings.model.isNotBlank() && settings.prompt.isNotBlank() && !settings.credentialRef.isNullOrBlank()
        val input = PipelineInput(recording, ui.local, ui.busy, ui.drive,
            ui.link == LinkStatus.CONNECTED && !ui.recovering, configured, current?.status ?: ProvenanceStatus.UNKNOWN, ui.documentHashes)
        val presentation = RecordingPipeline.calculate(input)
        RecordingBar(recording, presentation.badge, ui.local.recordingSubjects[name], canRecover, onBack, onRecover = { model.recover(listOf(name)) },
            canRedoVtt = canRedoVtt, canRedoMd = canRedoMd,
            onRedoVtt = { model.transcribe(setOf(name), RequestedWork.REDO_VTT_AND_MD) },
            onRedoMd = { model.transcribe(setOf(name), RequestedWork.REDO_MD) },
            onDelete = { model.requestPhoneDeletion(setOf(name)) }, onDeleteBox = { model.requestBoxDeletion(setOf(name)) })
        Column(
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PipelineCard(presentation) { action ->
                when (action) {
                    PipelineAction.CANCEL_VTT, PipelineAction.CANCEL_MD -> confirmCancel = true
                    PipelineAction.REMOVE_FROM_QUEUE -> model.cancelTranscription(name)
                    PipelineAction.RECOVER, PipelineAction.RETRY_RECOVERY -> model.recover(listOf(name))
                    PipelineAction.SEND, PipelineAction.RETRY_SEND -> model.send(listOf(name))
                    PipelineAction.CREATE_VTT, PipelineAction.CREATE_VTT_AND_MD, PipelineAction.CREATE_MD ->
                        model.transcribe(setOf(name), RequestedWork.MISSING)
                    PipelineAction.RETRY_VTT -> model.transcribe(setOf(name), RequestedWork.MISSING, resumeRecording = true)
                    PipelineAction.RETRY_MD -> task?.let { model.retrySynthesis(it.localId) }
                    PipelineAction.REDO_MD -> model.transcribe(setOf(name), RequestedWork.REDO_MD)
                    PipelineAction.OPEN_SETTINGS -> onSettings(presentation.settingsDestination(input))
                }
            }
            if (ui.local.driveEnabled && presentation.steps.any { it.stage == PipelineStage.DRIVE && it.status == PipelineStatus.DONE }) {
                SecondaryButton("Envoyer sur Drive…", enabled = !ui.sending, onClick = { model.send(listOf(name)) })
            }
            if (mp3 == null) MissingMp3() else if (playback != null) {
                Player(playback)
                FileLine(mp3.fileName, ui.phoneFolder)
            }
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
            RecordingDocuments(markdown = current?.markdown, provenanceLabel = label, mdPresent = md != null,
                mdError = document?.isFailure == true, hasVtt = vtt != null) {
                if (vtt == null) Notice("La transcription n'est pas encore disponible.") else
                    Transcript(model, name, vtt, playback, ui.phoneRevision, ui.local.speakerNames, speakerRenameUnavailableReason(activeTask != null))
            }
        }
    }
}

@Composable
internal fun RecordingDocuments(
    markdown: String?, provenanceLabel: String?,
    mdPresent: Boolean = markdown != null, mdError: Boolean = false, hasVtt: Boolean = false,
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
            }
            mdError -> Text("Le fichier MD n'est pas lisible.", style = Dictee.type.body, color = Dictee.colors.destructive)
            mdPresent -> Text("Lecture…", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            else -> {
                Text("La synthèse reste à créer", style = Dictee.type.rowTitle, color = Dictee.colors.text)
                Text(if (hasVtt) "Le VTT présent sera utilisé sans retranscrire l'audio." else "La synthèse pourra être créée lorsque le VTT sera disponible.", style = Dictee.type.caption, color = Dictee.colors.textSecondary)
            }
        }
    }
}

/** En-tête : le retour, le titre, la durée avec l'état, puis le menu. */
@Composable
private fun RecordingBar(
    recording: Recording, badge: String, subject: String?, canRecover: Boolean, onBack: () -> Unit, onRecover: () -> Unit,
    canRedoVtt: Boolean, canRedoMd: Boolean, onRedoVtt: () -> Unit, onRedoMd: () -> Unit, onDelete: () -> Unit, onDeleteBox: () -> Unit,
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
                text = subject ?: RecordName.detailTitle(recording.type, recording.name),
                style = Dictee.type.titleItem,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = (subject?.let { "${RecordName.detailTitle(recording.type, recording.name)} · " } ?: "") + duration(recording), style = Dictee.type.caption, color = colors.textSecondary, maxLines = 1)
                Text(badge, style = Dictee.type.captionStrong,
                    color = when (badge) { "Erreur" -> colors.destructive; "Sur Drive" -> colors.success; else -> colors.textSecondary })
            }
        }
        RecordingMenu(canRecover, onRecover, canRedoVtt, canRedoMd, onRedoVtt, onRedoMd, onDelete, recording.onBox, onDeleteBox)
    }
}

/** La durée estimée depuis la taille, par exemple « 41 min ». */
private fun duration(recording: Recording): String =
    RecordName.formatDuration(RecordName.durationSeconds(recording.size))

/** Menu de la fiche : « Récupérer à nouveau », grisé sans liaison avec le boîtier ou pendant une récupération. */
@Composable
internal fun RecordingMenu(canRecover: Boolean, onRecover: () -> Unit,
    canRedoVtt: Boolean, canRedoMd: Boolean, onRedoVtt: () -> Unit, onRedoMd: () -> Unit, onDelete: () -> Unit,
    onBox: Boolean, onDeleteBox: () -> Unit) {
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
            if (onBox) DropdownMenuItem(text = { Text("Supprimer du boîtier", style = Dictee.type.rowTitle) },
                onClick = { open = false; onDeleteBox() })
            DropdownMenuItem(text = { Text("Supprimer du téléphone", style = Dictee.type.rowTitle) },
                onClick = { open = false; onDelete() })
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
        }
    }
}

/** Carte à la place du lecteur quand le MP3 n'est pas dans le dossier du téléphone. */
@Composable
private fun MissingMp3() {
    Box(modifier = Modifier.fillMaxWidth().card().padding(16.dp)) {
        Text(text = "Le MP3 n'est pas encore sur le téléphone.", style = Dictee.type.body, color = Dictee.colors.textSecondary)
    }
}

/**
 * Rangée « Locuteurs » puis segments du VTT : un toucher place la lecture du MP3 au début du segment et la lance. Toucher une puce ou
 * l'étiquette d'un segment ouvre le dialogue « Locuteurs », sauf si [renameBlockedReason] en donne la raison. Un fichier illisible n'affecte que cette carte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transcript(
    model: AppModel, recordingName: String, vtt: PhoneFile, playback: Playback?, phoneRevision: Long,
    speakerBook: List<SpeakerName>, renameBlockedReason: String?,
) {
    val colors = Dictee.colors
    val cues by produceState<Result<List<VttCue>>?>(null, vtt.fileName, vtt.size, phoneRevision) {
        value = withContext(Dispatchers.IO) { model.readTranscript(vtt.fileName) }
    }
    var editing by remember { mutableStateOf(false) }
    val renameEnabled = renameBlockedReason == null
    Column(modifier = Modifier.fillMaxWidth().card().padding(vertical = 4.dp)) {
        Text(text = "Transcription", style = Dictee.type.rowTitle, color = colors.text, modifier = Modifier.padding(12.dp))
        val result = cues
        val loaded = result?.getOrNull().orEmpty()
        val labels = loaded.mapNotNull { it.speaker }.distinct()
        if (labels.isNotEmpty()) {
            Text("Locuteurs", style = Dictee.type.label, color = colors.text, modifier = Modifier.padding(horizontal = 12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEach { FilterPill("$it ✎", selected = false, enabled = renameEnabled, onClick = { editing = true }) }
            }
            renameBlockedReason?.let {
                Text(it, style = Dictee.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }
        }
        if (editing) {
            SpeakersDialog(
                labels = labels, firstCueMs = loaded.mapNotNull { cue -> cue.speaker?.let { it to cue.startMs } }.distinctBy { it.first }.toMap(),
                book = speakerBook, playback = playback,
                onSave = { renames -> editing = false; model.renameSpeakers(recordingName, renames) },
                onDismiss = { editing = false },
            )
        }
        when {
            result == null -> Text("Lecture…", style = Dictee.type.caption, color = colors.textSecondary, modifier = Modifier.padding(12.dp))
            result.isFailure -> Text("Le fichier VTT n'est pas lisible.", style = Dictee.type.caption, color = colors.destructive, modifier = Modifier.padding(12.dp))
            loaded.isEmpty() -> Text("Aucune parole détectée.", style = Dictee.type.caption, color = colors.textSecondary, modifier = Modifier.padding(12.dp))
            else -> loaded.forEach { cue ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .pressable(radius = 0.dp, enabled = playback != null && playback.status != PlaybackStatus.FAILED, onClick = { playback?.playFrom(cue.startMs) })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val meta = Dictee.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    Row {
                        Text(text = formatClock(cue.startMs.toInt()), style = meta, color = colors.textSecondary)
                        cue.speaker?.let { speaker ->
                            Text(text = " · ", style = meta, color = colors.textSecondary)
                            Text(
                                text = speaker, style = meta.copy(textDecoration = if (renameEnabled) TextDecoration.Underline else null),
                                color = colors.textSecondary,
                                modifier = Modifier.pressable(enabled = renameEnabled, onClick = { editing = true }),
                            )
                        }
                    }
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
