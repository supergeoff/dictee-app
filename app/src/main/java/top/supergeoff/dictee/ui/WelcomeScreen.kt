package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.R
import top.supergeoff.dictee.UiState

/**
 * Écran affiché tant qu'aucun boîtier n'est associé. Le bouton laisse à l'activité la demande de permission Bluetooth,
 * puis l'ouverture de la feuille de recherche. Les réglages restent accessibles avant l'association.
 */
@Composable
fun WelcomeScreen(ui: UiState, onSearch: () -> Unit, onSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Dictee.colors.background)) {
        Header(link = ui.link, deviceName = null, onPill = {}, onSettings = onSettings)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            RecorderBadge()
            WelcomeMessage()
            PrimaryButton(
                text = "Rechercher le boîtier",
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_search,
                onClick = onSearch,
            )
        }
    }
}

/** Rond de 72 dp sur le fond discret `muted`, portant le pictogramme du boîtier. */
@Composable
private fun RecorderBadge() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Dictee.colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_recorder),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = Dictee.colors.text,
        )
    }
}

@Composable
private fun WelcomeMessage() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Associe ton enregistreur",
            style = Dictee.type.titleSection,
            color = Dictee.colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "L'app récupère les enregistrements de ton MECHEN par Bluetooth. Tu n'auras à l'associer qu'une fois.",
            style = Dictee.type.body,
            color = Dictee.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
