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
import top.supergeoff.dictee.R
import top.supergeoff.dictee.RecordName
import top.supergeoff.dictee.RecordType
import top.supergeoff.dictee.Recording
import top.supergeoff.dictee.Recordings
import top.supergeoff.dictee.UiState
import java.time.LocalDate

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
        ProgressBars(ui.recovery, ui.send)
        Filters(ui.recordings, filter) { filter = it }
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = model::refresh, modifier = Modifier.weight(1f)) {
            when {
                ui.recordings.isEmpty() -> EmptyList(emptyListText(ui.link, ui.refreshing))
                shown.isEmpty() -> EmptyList(NO_MATCH_TEXT)
                else -> RecordingList(
                    recordings = shown,
                    checked = checked,
                    onClick = { name -> if (checked != null) selection.toggle(name) else onOpen(name) },
                    onLongClick = { name -> if (checked == null) selection.start(name) },
                )
            }
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
        items(groups, key = { it.second.first().name }) { (day, group) -> DayGroup(day, group, checked, onClick, onLongClick) }
    }
}

/** Un jour : son titre en `overline`, puis ses lignes dans une carte. */
@Composable
private fun DayGroup(
    day: String,
    recordings: List<Recording>,
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
                RecordingRow(
                    recording = recording,
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
private fun RecordingRow(recording: Recording, checked: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
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
                text = RecordName.rowTitle(recording.type, recording.name),
                style = Dictee.type.rowTitle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = details(recording), style = Dictee.type.caption, color = colors.textSecondary, maxLines = 1)
        }
        StateBadge(recording.state)
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
    val sendNames = checked?.let { names -> ui.toSend.map { it.name }.filter { it in names } }
    val recoverCount = recoverNames?.size ?: ui.toRecover.size
    val sendCount = sendNames?.size ?: ui.toSend.size
    Column(modifier = Modifier.fillMaxWidth().background(Dictee.colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = Dictee.colors.border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.local.driveEnabled) {
                SecondaryButton(
                    text = if (ui.sending) "Envoi…" else "Envoyer ($sendCount)",
                    enabled = !ui.sending && sendCount > 0,
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_upload,
                ) {
                    model.send(sendNames)
                    onDone()
                }
            }
            PrimaryButton(
                text = if (ui.recovering) "Récupération…" else "Récupérer ($recoverCount)",
                enabled = ui.link == LinkStatus.CONNECTED && !ui.recovering && recoverCount > 0,
                modifier = Modifier.weight(1f),
                icon = R.drawable.ic_download,
            ) {
                model.recover(recoverNames)
                onDone()
            }
        }
    }
}
