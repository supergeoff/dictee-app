package top.supergeoff.dictee.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.LinkStatus
import top.supergeoff.dictee.FileKind
import top.supergeoff.dictee.ProvenanceStatus
import top.supergeoff.dictee.R
import top.supergeoff.dictee.RecordName
import top.supergeoff.dictee.RecordType
import top.supergeoff.dictee.Recording
import top.supergeoff.dictee.Recordings
import top.supergeoff.dictee.UiState
import top.supergeoff.dictee.isTerminal
import top.supergeoff.dictee.transcriptionNotificationLabel
import top.supergeoff.dictee.recordingListPresentation
import top.supergeoff.dictee.PipelinePresentation
import top.supergeoff.dictee.PipelineStatus
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filtres de la liste, chacun avec son compteur. « Sur le boîtier » garde ce qui n'est pas encore récupéré.
 * « À relire » n'a pas encore d'état au lot 1 : il reste vide, jusqu'aux lots qui produisent la synthèse.
 */
private enum class Filter(val label: String) {
    ALL("Tous"),
    REVIEW("À relire"),
    ON_BOX("Sur le boîtier"),
    ;

    fun matches(recording: Recording): Boolean = when (this) {
        ALL -> true
        REVIEW -> false
        ON_BOX -> recording.onBox && recording.phoneFiles.isEmpty()
    }
}

/** Lignes cochées, `null` hors sélection. Décocher la dernière ligne quitte la sélection. */
@Stable
private class Selection {
    var names by mutableStateOf<Set<String>?>(null)
        private set

    fun start(name: String) {
        names = setOf(name)
    }

    fun toggle(name: String) {
        val current = names ?: return
        names = (if (name in current) current - name else current + name).ifEmpty { null }
    }

    fun addAll(more: Collection<String>) {
        names = names.orEmpty() + more
    }

    /** Oublie les lignes qui ne sont plus dans la liste ; si aucune ligne cochée ne reste, quitte la sélection. */
    fun retain(present: Set<String>) {
        val current = names ?: return
        val kept = current.filterTo(HashSet()) { it in present }
        if (kept.size != current.size) names = kept.ifEmpty { null }
    }

    fun clear() {
        names = null
    }
}

/**
 * Liste des enregistrements, groupée par jour, du plus récent au plus ancien. Un toucher ouvre la fiche, un appui long passe
 * en sélection. Tirer la liste relit le boîtier, le téléphone et Drive. Les boutons du bas portent sur tout ce qui manque,
 * ou sur les lignes cochées en sélection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(ui: UiState, model: AppModel, onOpen: (name: String) -> Unit, onSettings: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    val selection = remember { Selection() }
    var boxDeletionTargets by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(ui.deletingBox, ui.boxDeletionResult) {
        val targets = boxDeletionTargets
        val result = ui.boxDeletionResult
        if (targets != null && !ui.deletingBox && result != null) {
            selection.retain(top.supergeoff.dictee.selectionAfterBoxDeletion(selection.names.orEmpty(), targets intersect result.confirmed, ui.recordings))
            boxDeletionTargets = null
        }
    }
    ui.boxDeletionRequest?.let { plan ->
        BoxDeletionDialog(plan, ui.deletingBox, onConfirm = {
            boxDeletionTargets = plan.names.toSet()
            model.confirmBoxDeletion()
        }, onDismiss = model::cancelBoxDeletion)
    }
    var deletionTargets by remember { mutableStateOf<Set<String>?>(null) }
    var deletionRevision by remember { mutableStateOf(0L) }
    var deletionStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ui.phoneRevision, ui.deletingPhone) {
        val targets = deletionTargets
        if (targets != null && ui.deletingPhone) deletionStarted = true
        if (targets != null && !ui.deletingPhone && (deletionStarted || ui.phoneRevision != deletionRevision)) {
            if (ui.phoneRevision != deletionRevision) {
                selection.retain(top.supergeoff.dictee.selectionAfterPhoneDeletion(selection.names.orEmpty(), targets, ui.recordings))
            }
            deletionTargets = null
        }
    }
    ui.phoneDeletionRequest?.let { plan ->
        PhoneDeletionDialog(plan, ui.deletingPhone, onConfirm = {
            deletionTargets = plan.recordings.mapTo(HashSet()) { it.name }
            deletionRevision = ui.phoneRevision
            deletionStarted = false
            model.confirmPhoneDeletion()
        }, onDismiss = model::cancelPhoneDeletion)
    }
    val shown = remember(ui.recordings, filter) { ui.recordings.filter { filter.matches(it) } }
    val present = remember(ui.recordings) { ui.recordings.mapTo(HashSet()) { it.name } }
    // Une ligne cochée qui disparaît de la liste est oubliée, pour ne pas être recochée si elle revient.
    LaunchedEffect(present) { selection.retain(present) }
    // Tant que l'oubli n'est pas passé, une sélection sans ligne présente est déjà hors sélection : jamais « 0 sélectionné ».
    val checked = selection.names?.filterTo(HashSet()) { it in present }?.takeIf { it.isNotEmpty() }
    BackHandler(enabled = checked != null) { selection.clear() }
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        if (checked == null) {
            Header(ui.link, ui.local.deviceName ?: "MECHEN", onPill = model::connectBox, onSettings = onSettings)
        } else {
            SelectionBar(checked.size, onClose = selection::clear, onAll = { selection.addAll(shown.map { it.name }) })
        }
        ProgressBars(ui.recovery, ui.send, ui.transcription,
            ui.transcription?.let { transcriptionNotificationLabel(it, ui.local.transcriptionTasks) })
        Filters(ui.recordings, filter) { filter = it }
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = model::refresh, modifier = Modifier.weight(1f)) {
            when {
                ui.recordings.isEmpty() -> EmptyList(emptyListText(ui.link, ui.refreshing))
                shown.isEmpty() -> EmptyList(NO_MATCH_TEXT)
                else -> RecordingList(
                    recordings = shown,
                    ui = ui,
                    model = model,
                    checked = checked,
                    onClick = { name -> if (checked != null) selection.toggle(name) else onOpen(name) },
                    onLongClick = { name -> if (checked == null) selection.start(name) },
                )
            }
        }
        if (checked != null) {
            val boxNames = ui.recordings.filter { it.onBox && it.name in checked }.mapTo(HashSet()) { it.name }
            if (boxNames.isNotEmpty()) SecondaryButton("Supprimer du boîtier", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = { model.requestBoxDeletion(boxNames) })
            SelectionDeletionButton { model.requestPhoneDeletion(checked) }
        }
        ActionBar(ui, model, checked, onDone = selection::clear)
    }
}

/** Texte affiché au centre quand des enregistrements existent mais qu'aucun ne correspond au filtre choisi. */
private const val NO_MATCH_TEXT = "Aucun enregistrement pour ce filtre."

/** Texte de la liste vide : la lecture du boîtier tant qu'elle dure, sinon l'état de la liaison. */
private fun emptyListText(link: LinkStatus, refreshing: Boolean): String = when {
    link == LinkStatus.CONNECTING || refreshing -> "Lecture du boîtier…"
    link == LinkStatus.CONNECTED -> "Aucun enregistrement sur le boîtier."
    else -> "Boîtier hors de portée. Touche la pastille pour relancer."
}

/** Liste vide : le texte est centré dans une liste, pour que tirer vers le bas relise encore. */
@Composable
private fun EmptyList(text: String) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillParentMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(text = text, style = Dictee.type.body, color = Dictee.colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Barre de sélection, à la place de l'en-tête : la croix quitte, « Tout » coche les lignes du filtre en cours. */
@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconAction(R.drawable.ic_close, "Quitter la sélection", onClose)
        Text(
            text = if (count > 1) "$count sélectionnés" else "$count sélectionné",
            style = Dictee.type.titleItem,
            color = Dictee.colors.text,
        )
        Spacer(Modifier.weight(1f))
        CompactButton(text = "Tout", onClick = onAll)
    }
}

@Composable
private fun Filters(recordings: List<Recording>, selected: Filter, onSelect: (Filter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Filter.entries.forEach { filter ->
            FilterPill(filter.label, filter == selected, count = recordings.count { filter.matches(it) }) { onSelect(filter) }
        }
    }
}

/** [checked] est `null` hors sélection, sinon les noms cochés. */
@Composable
private fun RecordingList(
    recordings: List<Recording>,
    ui: UiState,
    model: AppModel,
    checked: Set<String>?,
    onClick: (name: String) -> Unit,
    onLongClick: (name: String) -> Unit,
) {
    val groups = remember(recordings) { Recordings.groupByDay(recordings, LocalDate.now()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Le libellé du jour n'a pas d'année : la clé est le premier enregistrement du groupe, unique par construction.
        items(groups, key = { it.second.first().name }) { (day, group) -> DayGroup(day, group, ui, model, checked, onClick, onLongClick) }
    }
}

/** Un jour : son titre en `overline`, puis ses lignes dans une carte. */
@Composable
private fun DayGroup(
    day: String,
    recordings: List<Recording>,
    ui: UiState,
    model: AppModel,
    checked: Set<String>?,
    onClick: (name: String) -> Unit,
    onLongClick: (name: String) -> Unit,
) {
    val colors = Dictee.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(day)
        Column(modifier = Modifier.fillMaxWidth().card()) {
            recordings.forEachIndexed { index, recording ->
                if (index > 0) HorizontalDivider(color = colors.border)
                val md = recording.phoneFiles[FileKind.MD]
                val vtt = recording.phoneFiles[FileKind.VTT]
                val provenance by produceState(ProvenanceStatus.UNKNOWN, md, vtt, ui.local.synthesisProvenance, ui.local.transcriptionTasks, ui.refreshing, ui.phoneRevision) {
                    value = ProvenanceStatus.UNKNOWN
                    if (md != null) value = withContext(Dispatchers.IO) { model.readSynthesis(md.fileName, vtt?.fileName).getOrNull()?.status } ?: ProvenanceStatus.UNKNOWN
                }
                RecordingRow(
                    recording = recording,
                    presentation = recordingListPresentation(recording, ui, provenance),
                    subject = ui.local.recordingSubjects[recording.name],
                    checked = checked?.let { recording.name in it },
                    onClick = { onClick(recording.name) },
                    onLongClick = { onLongClick(recording.name) },
                )
            }
        }
    }
}

/** Ligne d'enregistrement. [checked] est `null` hors sélection : le pictogramme du type laisse alors la place à la case. */
@Composable
private fun RecordingRow(recording: Recording, presentation: PipelinePresentation, subject: String?, checked: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = Dictee.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked == true) colors.accent else colors.surface)
            .pressable(radius = 0.dp, role = null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (checked == null) {
            IconTile(if (recording.type == RecordType.CALL) R.drawable.ic_call else R.drawable.ic_note)
        } else {
            SelectionMark(checked)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subject ?: RecordName.rowTitle(recording.type, recording.name),
                style = Dictee.type.rowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val rank = presentation.queueRank?.takeIf { presentation.badge == "En attente" }
            val date = if (subject == null) "" else "${RecordName.detailTitle(recording.type, recording.name)} · "
            Text(text = date + details(recording) + (rank?.let { " · ${it}e dans la file" } ?: ""), style = Dictee.type.caption, color = colors.textSecondary, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(presentation.badge, style = Dictee.type.captionStrong,
                    color = when (presentation.badge) { "Erreur" -> colors.destructive; "Sur Drive" -> colors.success; else -> colors.textSecondary })
                presentation.steps.forEach { step ->
                    val color = when (step.status) {
                        PipelineStatus.DONE -> colors.success
                        PipelineStatus.RUNNING -> colors.primary
                        PipelineStatus.ERROR -> colors.destructive
                        PipelineStatus.WAITING -> colors.warning
                        PipelineStatus.TODO -> colors.border
                    }
                    Box(Modifier.size(8.dp).background(color, CircleShape)
                        .semantics { contentDescription = "${step.stage.title()} : ${step.label}" })
                }
            }
        }
    }
}

/** La durée puis la taille, par exemple « 23 min · 5,5 Mo ». */
private fun details(recording: Recording): String =
    "${RecordName.formatDuration(RecordName.durationSeconds(recording.size))} · ${RecordName.formatSize(recording.size)}"

@Composable
private fun SelectionMark(checked: Boolean) {
    val colors = Dictee.colors
    val shape = RoundedCornerShape(Tokens.RADIUS_SM_DP.dp)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (checked) colors.primary else colors.surface)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.textSecondary.copy(alpha = 0.6f), shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.onPrimary,
            )
        }
    }
}

/**
 * Boutons du bas. Hors sélection (`checked` nul), « Récupérer » prend tout ce qui manque sur le boîtier et « Envoyer » tout ce
 * qui manque dans Drive. En sélection, ils portent sur les lignes cochées : celles du boîtier pour récupérer, celles à envoyer
 * pour envoyer. Une action quitte ensuite la sélection. Le bouton « Envoyer » n'existe que si Drive est activé.
 */
@Composable
private fun ActionBar(ui: UiState, model: AppModel, checked: Set<String>?, onDone: () -> Unit) {
    val recoverNames = checked?.let { names -> ui.local.boxList.map { it.name }.filter { it in names } }
    val sendNames = checked?.let { names -> Recordings.sendable(ui.recordings, ui.local.errors).map { it.name }.filter { it in names } }
    val active = ui.local.transcriptionTasks.filter { !it.phase.isTerminal() }.mapTo(mutableSetOf()) { it.recordingName }
    val transcribe = Recordings.toTranscribe(ui.recordings, checked, ui.local.errors, active).candidates.map { it.name }
    val recoverCount = recoverNames?.size ?: ui.toRecover.size
    val sendCount = if (checked == null) ui.toSend.size else ui.toSend.count { it.name in checked }
    ActionBarButtons(
        recoverText = if (ui.recovering) "Récupération…" else "Récupérer ($recoverCount)",
        recoverEnabled = ui.link == LinkStatus.CONNECTED && !ui.recovering && recoverCount > 0,
        transcribeCount = transcribe.size,
        sendText = if (ui.sending) "Envoi…" else "Envoyer ($sendCount)",
        sendEnabled = !ui.sending && if (checked != null) sendNames.orEmpty().isNotEmpty() else sendCount > 0,
        driveEnabled = ui.local.driveEnabled,
        onRecover = { model.recover(recoverNames); onDone() },
        onTranscribe = { model.transcribe(checked); onDone() },
        onSend = { model.send(sendNames, if (checked != null) onDone else null) },
    )
}

@Composable
internal fun ActionBarButtons(
    recoverText: String, recoverEnabled: Boolean, transcribeCount: Int,
    sendText: String, sendEnabled: Boolean, driveEnabled: Boolean,
    onRecover: () -> Unit, onTranscribe: () -> Unit, onSend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Dictee.colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = Dictee.colors.border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionBarButton(recoverText, primary = true, enabled = recoverEnabled, modifier = Modifier.weight(1f), onClick = onRecover)
            ActionBarButton("VTT + MD ($transcribeCount)", primary = false, enabled = transcribeCount > 0, modifier = Modifier.weight(1f), onClick = onTranscribe)
            if (driveEnabled) {
                ActionBarButton(sendText, primary = false, enabled = sendEnabled, modifier = Modifier.weight(1f), onClick = onSend)
            }
        }
    }
}

@Composable
internal fun SelectionDeletionButton(onDelete: () -> Unit) {
    SecondaryButton("Supprimer du téléphone", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), onClick = onDelete)
}
