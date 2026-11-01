package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import top.supergeoff.dictee.DisplayState
import top.supergeoff.dictee.LinkStatus
import top.supergeoff.dictee.Progress
import top.supergeoff.dictee.R
import java.util.Locale

/** Rayon des boutons et des tuiles, puis rayon des cartes : ceux de [Tokens]. */
internal val ControlShape = RoundedCornerShape(Tokens.RADIUS_DP.dp)
internal val CardShape = RoundedCornerShape(Tokens.CARD_RADIUS_DP.dp)
private val CompactShape = RoundedCornerShape(8.dp)

/** Style d'un élément de la maquette : la police du thème, avec la taille, l'interligne et la graisse de l'élément. */
@Composable
@ReadOnlyComposable
internal fun sized(size: Int, line: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    Dictee.type.body.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

/** Rond de la couleur du texte, micro de la couleur du fond : l'icône s'inverse en thème sombre. */
@Composable
fun AppIcon(size: Dp = 32.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Dictee.colors.text),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            modifier = Modifier.size(size * 0.5625f),
            tint = Dictee.colors.background,
        )
    }
}

/** En-tête de la liste et du premier lancement : l'icône, le nom, la pastille du boîtier et les réglages. */
@Composable
fun Header(link: LinkStatus, deviceName: String?, onPill: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon()
        Text(
            text = "DICTÉE",
            style = sized(16, 20, FontWeight.Bold).copy(letterSpacing = 0.06.em),
            color = Dictee.colors.text,
        )
        Spacer(Modifier.weight(1f))
        DevicePill(link, deviceName, onPill)
        IconAction(R.drawable.ic_settings, "Réglages", onSettings)
    }
}

/** Pastille du boîtier : « Aucun boîtier » sans nom, point plein quand le boîtier est connecté, point vide sinon. */
@Composable
private fun DevicePill(link: LinkStatus, deviceName: String?, onClick: () -> Unit) {
    val colors = Dictee.colors
    val connected = link == LinkStatus.CONNECTED
    val tint = if (connected) colors.text else colors.textSecondary
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(1.dp, colors.border, CircleShape)
            .clickable(enabled = deviceName != null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .then(if (connected) Modifier.background(tint) else Modifier.border(1.5.dp, tint, CircleShape)),
        )
        Text(
            text = deviceName ?: "Aucun boîtier",
            style = sized(12, 16, FontWeight.Medium),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bouton carré de 40 dp portant un pictogramme de 20 dp : les réglages de l'en-tête, le retour, la sortie de la sélection. */
@Composable
internal fun IconAction(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(ControlShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = Dictee.colors.text,
        )
    }
}

/** Tuile de 36 dp sur le fond de l'écran, avec un pictogramme de 18 dp : le type d'une ligne, le boîtier trouvé. */
@Composable
internal fun IconTile(icon: Int) {
    Box(
        modifier = Modifier.size(36.dp).clip(ControlShape).background(Dictee.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Dictee.colors.text,
        )
    }
}

/** Seul l'état d'erreur est en couleur au lot 1 ; les autres reprennent le contour et le texte secondaire. */
@Composable
fun StateBadge(state: DisplayState) {
    val colors = Dictee.colors
    val error = state is DisplayState.Error
    val content = if (error) colors.errorText else colors.textSecondary
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(if (error) colors.errorBackground else colors.surface)
            .border(1.dp, if (error) colors.errorBackground else colors.border, CircleShape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BadgeIcon(state, content)
        Text(
            text = state.label,
            style = sized(12, 16, if (error) FontWeight.SemiBold else FontWeight.Medium),
            color = content,
            maxLines = 1,
        )
    }
}

/** Pictogramme de 12 dp devant le libellé : un arc qui avance ou tourne pendant un traitement, rien pour une erreur. */
@Composable
private fun BadgeIcon(state: DisplayState, tint: Color) {
    when (state) {
        is DisplayState.Recovering -> CircularProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.size(12.dp),
            color = tint,
            strokeWidth = 2.dp,
            trackColor = Color.Transparent,
        )
        DisplayState.Sending -> CircularProgressIndicator(modifier = Modifier.size(12.dp), color = tint, strokeWidth = 2.dp)
        DisplayState.OnDrive -> BadgeSymbol(R.drawable.ic_cloud, tint)
        DisplayState.Ready, DisplayState.Recovered -> BadgeSymbol(R.drawable.ic_check, tint)
        DisplayState.OnBox -> BadgeSymbol(R.drawable.ic_recorder, tint)
        is DisplayState.Error -> Unit
    }
}

@Composable
private fun BadgeSymbol(icon: Int, tint: Color) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(12.dp), tint = tint)
}

/** Bouton plein, sur la couleur primaire ; grisé quand il est désactivé. [icon] est un pictogramme de 18 dp devant le texte. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    val colors = Dictee.colors
    ButtonBox(
        text = text,
        style = sized(15, 20, FontWeight.SemiBold),
        icon = icon,
        enabled = enabled,
        modifier = modifier,
        container = if (enabled) colors.primary else colors.border,
        content = if (enabled) colors.onPrimary else colors.textSecondary,
        outline = null,
        onClick = onClick,
    )
}

/** Bouton à contour, sur la surface ; grisé quand il est désactivé. [icon] est un pictogramme de 18 dp devant le texte. */
@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    val colors = Dictee.colors
    ButtonBox(
        text = text,
        style = sized(15, 20, FontWeight.Medium),
        icon = icon,
        enabled = enabled,
        modifier = modifier,
        container = if (enabled) colors.surface else colors.border,
        content = if (enabled) colors.text else colors.textSecondary,
        outline = colors.border,
        onClick = onClick,
    )
}

@Composable
private fun ButtonBox(
    text: String,
    style: TextStyle,
    icon: Int?,
    enabled: Boolean,
    modifier: Modifier,
    container: Color,
    content: Color,
    outline: Color?,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(Tokens.BUTTON_HEIGHT_DP.dp)
            .clip(ControlShape)
            .background(container)
            .then(if (outline != null) Modifier.border(1.dp, outline, ControlShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
            }
            Text(text = text, style = style, color = content, maxLines = 1)
        }
    }
}

/** Petit bouton de 36 dp : « Associer » en plein (`primary`), « Tout » à contour. Inactif ([enabled] faux), il prend les couleurs d'un bouton inactif. */
@Composable
internal fun CompactButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Dictee.colors
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(CompactShape)
            .background(if (!enabled) colors.border else if (primary) colors.primary else colors.surface)
            .then(if (primary || !enabled) Modifier else Modifier.border(1.dp, colors.border, CompactShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (primary) 14.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = sized(14, 20, if (primary) FontWeight.SemiBold else FontWeight.Medium),
            color = if (!enabled) colors.textSecondary else if (primary) colors.onPrimary else colors.text,
            maxLines = 1,
        )
    }
}

/** Une ligne et sa barre par traitement en cours ; rien ne s'affiche sans récupération ni envoi. */
@Composable
fun ProgressBars(recovery: Progress?, send: Progress?) {
    if (recovery == null && send == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        recovery?.let { ProgressLine(it) }
        send?.let { ProgressLine(it) }
    }
}

@Composable
private fun ProgressLine(progress: Progress) {
    val colors = Dictee.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = progress.label(), style = sized(12, 16), color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.border),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
    }
}

/** Titre de section en capitales, comme les jours de la liste et les rubriques des réglages. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.FRENCH),
        style = sized(11, 16, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
        color = Dictee.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Barre des écrans empilés : le retour, le titre, puis les actions de l'écran. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconAction(R.drawable.ic_back, "Retour", onBack)
        Text(
            text = title,
            style = sized(16, 20, FontWeight.SemiBold),
            color = Dictee.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
