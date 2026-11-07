package top.supergeoff.dictee.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.supergeoff.dictee.BleScanner
import top.supergeoff.dictee.R
import top.supergeoff.dictee.connectedBoxes

private const val SEARCH_SECONDS = 15
private const val HELP = "Ton boîtier n'apparaît pas ? Vérifie qu'il est allumé et que DOWAY est fermée."
private const val UNAVAILABLE_TEXT = "Bluetooth indisponible ou désactivé"

/** Phase de la recherche : en cours, finie, ou impossible quand le Bluetooth est absent ou éteint. */
private enum class Phase { RUNNING, DONE, UNAVAILABLE }

/** Message d'un boîtier déjà connecté au téléphone : il ne s'annonce plus, donc la recherche ne le trouve pas. */
private fun busyMessage(name: String): String =
    "$name est déjà connecté au téléphone par une autre app, sans doute DOWAY. " +
        "Force l'arrêt de DOWAY dans les réglages Android, puis relance la recherche."

/** État de la recherche : les boîtiers vus, le temps restant, et le boîtier déjà connecté à une autre app quand rien n'est trouvé. */
@Stable
private class BoxSearch {
    val boxes = mutableStateListOf<Pair<String, String>>()   // nom, adresse
    var phase by mutableStateOf(Phase.RUNNING)
    var secondsLeft by mutableIntStateOf(SEARCH_SECONDS)
    var busyName by mutableStateOf<String?>(null)
    var round by mutableIntStateOf(0)   // monte à chaque « Relancer » et relance la recherche

    /**
     * Cherche pendant [SEARCH_SECONDS] secondes. L'annulation de la coroutine, avec la feuille, coupe la recherche.
     * [beforeScan] ferme la liaison de l'app, qui sinon empêcherait le boîtier de s'annoncer : la recherche attend la fin de sa fermeture.
     */
    suspend fun scan(context: Context, beforeScan: suspend () -> Unit) {
        boxes.clear()
        busyName = null
        phase = Phase.RUNNING
        secondsLeft = SEARCH_SECONDS
        beforeScan()
        val scanner = BleScanner(context)
        val started = try {
            scanner.start { name, address -> boxes += name to address }
        } catch (e: SecurityException) {
            false
        }
        if (!started) {
            phase = Phase.UNAVAILABLE
            return
        }
        try {
            for (left in SEARCH_SECONDS downTo 1) {
                secondsLeft = left
                delay(1_000)
            }
        } finally {
            scanner.stop()
        }
        phase = Phase.DONE
        if (boxes.isEmpty()) busyName = withContext(Dispatchers.IO) { connectedBoxes(context).firstOrNull()?.first }
    }
}

/**
 * Feuille de recherche du boîtier, ouverte par-dessus l'écran de premier lancement ou depuis les réglages.
 * La recherche démarre à l'ouverture : l'appelant a déjà obtenu les permissions Bluetooth.
 * [beforeSearch] est appelé avant chaque recherche, à l'ouverture et à chaque « Relancer » : il ferme la liaison de l'app, et la recherche
 * attend la fin de sa fermeture. Un boîtier encore connecté après cela l'est donc par une autre app.
 * [onAssociate] et [onDismiss] sont appelés une fois la feuille fermée ; l'appelant la retire alors de l'écran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssociateSheet(beforeSearch: suspend () -> Unit, onAssociate: (name: String, address: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val search = remember { BoxSearch() }
    LaunchedEffect(search.round) { search.scan(context, beforeSearch) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) action() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Tokens.CARD_RADIUS_DP.dp, topEnd = Tokens.CARD_RADIUS_DP.dp),
        scrimColor = Dictee.colors.overlay,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 36.dp, height = 4.dp) },
    ) {
        SheetContent(
            search = search,
            onAssociate = { name, address -> closeThen { onAssociate(name, address) } },
            onCancel = { closeThen(onDismiss) },
        )
    }
}

@Composable
private fun SheetContent(search: BoxSearch, onAssociate: (name: String, address: String) -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchTitle(search.phase, search.secondsLeft, onRetry = { search.round++ })
        search.boxes.forEach { (name, address) -> BoxRow(name, address) { onAssociate(name, address) } }
        HelpNote(search.busyName?.let(::busyMessage) ?: HELP)
        SecondaryButton(text = "Annuler", modifier = Modifier.fillMaxWidth(), onClick = onCancel)
    }
}

/** Titre de la feuille, puis le temps restant pendant la recherche, ou « Relancer » quand elle est finie. */
@Composable
private fun SearchTitle(phase: Phase, secondsLeft: Int, onRetry: () -> Unit) {
    val secondary = Dictee.colors.textSecondary
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Recherche du boîtier",
            style = Dictee.type.titleModal,
            color = Dictee.colors.text,
        )
        when (phase) {
            Phase.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = secondary, strokeWidth = 2.dp)
                Text(text = "Recherche en cours · $secondsLeft s", style = Dictee.type.caption, color = secondary)
            }
            Phase.DONE -> RetryLink(onRetry)
            Phase.UNAVAILABLE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = UNAVAILABLE_TEXT, style = Dictee.type.caption, color = secondary)
                RetryLink(onRetry)
            }
        }
    }
}

@Composable
private fun RetryLink(onClick: () -> Unit) {
    Text(
        text = "Relancer",
        style = Dictee.type.label.copy(textDecoration = TextDecoration.Underline),
        color = Dictee.colors.text,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .pressable(radius = 4.dp, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** Une ligne par boîtier MECHEN trouvé : son nom, son adresse, et « Associer ». */
@Composable
private fun BoxRow(name: String, address: String, onAssociate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ElementShape)
            .border(1.dp, Dictee.colors.border, ElementShape)
            .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(R.drawable.ic_recorder)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = Dictee.type.rowTitle,
                color = Dictee.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = address,
                style = Dictee.type.mono,
                color = Dictee.colors.textSecondary,
            )
        }
        CompactButton(text = "Associer", primary = true, onClick = onAssociate)
    }
}

/** Aide affichée pendant et après la recherche, en encart neutre ; elle devient le message du boîtier déjà connecté quand il y en a un. */
@Composable
private fun HelpNote(text: String) {
    Notice(text = text)
}
