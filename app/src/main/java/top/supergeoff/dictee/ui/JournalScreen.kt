package top.supergeoff.dictee.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.supergeoff.dictee.AppModel
import top.supergeoff.dictee.Journal
import top.supergeoff.dictee.JournalFilter
import top.supergeoff.dictee.JournalLine
import top.supergeoff.dictee.R
import top.supergeoff.dictee.Recordings
import top.supergeoff.dictee.Source
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Autorité du `FileProvider` du manifeste, qui n'expose que `cacheDir/export/`. */
private const val FILES_AUTHORITY = "top.supergeoff.dictee.files"

private val MonthDayFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)

/**
 * Journal de l'app, ouvert depuis les Réglages. Les lignes sont relues hors du fil principal à chaque changement de
 * [Journal.revision], filtrées, puis groupées par jour du plus récent au plus ancien. « Copier » met les lignes affichées dans le
 * presse-papiers, « Exporter » partage un fichier texte des sept jours, « Vider » efface tout après confirmation.
 */
@Composable
fun JournalScreen(model: AppModel, onBack: () -> Unit) {
    val journal = model.journal
    val revision by journal.revision.collectAsState()
    var readFailed by remember { mutableStateOf(false) }
    val lines by produceState<List<JournalLine>?>(null, revision) {
        try {
            value = withContext(Dispatchers.IO) { journal.lines() }
            readFailed = false
        } catch (e: IOException) {
            // Fichier supprimé ou illisible pendant la lecture : la liste précédente reste affichée jusqu'à la prochaine révision.
            readFailed = true
        }
    }
    var filter by rememberSaveable { mutableStateOf(JournalFilter.ALL) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val shown = remember(lines, filter) { lines?.filter(filter::accepts) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        TopBar("Journal", onBack) {
            IconAction(R.drawable.ic_copy, "Copier") { shown?.takeIf { it.isNotEmpty() }?.let { copyLines(context, it) } }
            IconAction(R.drawable.ic_upload, "Exporter") { scope.launch { shareExport(context, journal) } }
            IconAction(R.drawable.ic_trash, "Vider le journal") { confirmClear = true }
        }
        JournalFilters(filter) { filter = it }
        LogCard(shown, unreadable = readFailed)
    }
    if (confirmClear) {
        DicteeDialog(
            title = "Vider le journal ?",
            confirm = "Vider",
            destructive = true,
            onConfirm = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { journal.clear() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Texte copié : les lignes dans l'ordre du temps, un bloc `== AAAA-MM-JJ ==` par jour, comme [Journal.exportText]. */
private fun plainText(lines: List<JournalLine>): String =
    lines.groupBy { it.date }.entries.sortedBy { it.key }.joinToString("\n") { (date, day) ->
        "== $date ==\n" + day.joinToString("") { "${it.time} ${it.source.name} ${it.message}\n" }
    }

private fun copyLines(context: Context, lines: List<JournalLine>) {
    val clip = ClipData.newPlainText("Journal", plainText(lines))
    val message = try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        "Journal copié"
    } catch (e: RuntimeException) {
        "Copie impossible"   // un presse-papiers refuse un texte trop gros pour le système
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** Écrit le texte exporté dans `cacheDir/export/`, hors du fil principal, puis ouvre le partage d'Android. */
private suspend fun shareExport(context: Context, journal: Journal) {
    val uri = try {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, "dictee-journal-${LocalDate.now()}.txt")
            file.writeText(journal.exportText())
            FileProvider.getUriForFile(context, FILES_AUTHORITY, file)
        }
    } catch (e: IOException) {
        Toast.makeText(context, "Export impossible", Toast.LENGTH_SHORT).show()
        withContext(Dispatchers.IO) { journal.error(Source.APP, "export du journal : ${e.message ?: e.javaClass.simpleName}") }
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun JournalFilters(selected: JournalFilter, onSelect: (JournalFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JournalFilter.entries.forEach { FilterPill(it.label, it == selected) { onSelect(it) } }
    }
}

/**
 * Carte des lignes, groupées par jour du plus récent au plus ancien ; `null` tant que la première lecture n'est pas finie.
 * Si cette première lecture a échoué ([unreadable]), la carte le dit au lieu de rester vide.
 */
@Composable
private fun ColumnScope.LogCard(lines: List<JournalLine>?, unreadable: Boolean) {
    val today = remember { LocalDate.now() }
    val days = remember(lines) { lines?.groupBy { it.date }?.toList()?.sortedByDescending { (date, _) -> date }.orEmpty() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .navigationBarsPadding()
            .card(),
    ) {
        val notice = when {
            lines != null && lines.isEmpty() -> "Aucune ligne."
            lines == null && unreadable -> "Lecture du journal impossible."
            else -> null
        }
        if (notice != null) {
            Text(
                text = notice,
                style = Dictee.type.caption,
                color = Dictee.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) {
            days.forEachIndexed { index, (date, day) ->
                item { DayHeader(dayTitle(date, today), first = index == 0) }
                items(day) { LogLine(it) }
            }
        }
    }
}

/** « AUJOURD'HUI · 31 OCTOBRE », « HIER · 30 OCTOBRE », puis le jour en toutes lettres, par exemple « JEUDI 29 OCTOBRE ». */
private fun dayTitle(date: LocalDate, today: LocalDate): String {
    val label = Recordings.dayLabel(date, today)
    val title = if (date == today || date == today.minusDays(1)) "$label · ${date.format(MonthDayFormat)}" else label
    return title.uppercase(Locale.FRENCH)
}

@Composable
private fun DayHeader(title: String, first: Boolean) {
    Text(
        text = title,
        style = Dictee.type.overline,
        color = Dictee.colors.textSecondary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = if (first) 2.dp else 12.dp, bottom = 8.dp),
    )
}

/** Une ligne : l'heure, la source, le message, en JetBrains Mono. Une erreur prend toute la rangée dans les couleurs `destructive`. */
@Composable
private fun LogLine(line: JournalLine) {
    val colors = Dictee.colors
    val style = Dictee.type.mono.copy(fontSize = 11.5.sp, lineHeight = 17.sp)
    val secondary = if (line.isError) colors.destructive else colors.textSecondary
    // Colonnes en sp : elles suivent la taille de police choisie dans les réglages du système.
    val timeWidth = with(LocalDensity.current) { 58.sp.toDp() }
    val sourceWidth = with(LocalDensity.current) { 44.sp.toDp() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line.isError) Modifier.background(colors.destructiveSurface) else Modifier)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(text = line.time, style = style, color = secondary, maxLines = 1, softWrap = false, modifier = Modifier.width(timeWidth))
        Text(text = line.source.name, style = style, color = secondary, maxLines = 1, softWrap = false, modifier = Modifier.width(sourceWidth))
        Text(text = line.message, style = style, color = if (line.isError) colors.destructive else colors.text, modifier = Modifier.weight(1f))
    }
}
