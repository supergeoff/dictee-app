package top.supergeoff.dictee.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.DisplayState
import top.supergeoff.dictee.LinkStatus
import top.supergeoff.dictee.Progress
import top.supergeoff.dictee.R
import java.util.Locale

/**
 * La marque de dictee (`brand-wordmark`) : un carré incliné de 10° de la couleur du texte, avec le D de la couleur du fond. Les deux
 * couches s'inversent en thème sombre (carré clair, D sombre), ce qui tient lieu du liseré que le design system demande pour un carré
 * presque noir sur fond sombre.
 */
@Composable
fun AppIcon(size: Dp = 32.dp) {
    Box(modifier = Modifier.size(size)) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_square),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = Dictee.colors.text,
        )
        Icon(
            painter = painterResource(R.drawable.ic_brand_d),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon()
        Text(text = "DICTÉE", style = Dictee.type.wordmark, color = Dictee.colors.text)
        Spacer(Modifier.weight(1f))
        DevicePill(link, deviceName, onPill)
        IconAction(R.drawable.ic_settings, "Réglages", onSettings)
    }
}

/** Pastille du boîtier : « Aucun boîtier » sans nom, point plein et teinte de réussite quand le boîtier est connecté, point vide et gris sinon. */
@Composable
private fun DevicePill(link: LinkStatus, deviceName: String?, onClick: () -> Unit) {
    val connected = link == LinkStatus.CONNECTED
    Badge(
        text = deviceName ?: "Aucun boîtier",
        tone = if (connected) BadgeTone.Success else BadgeTone.Neutral,
        onClick = if (deviceName != null) onClick else null,
    ) { tint -> StatusDot(tint, filled = connected) }
}

/** Point de 7 dp devant le libellé d'une pastille (`badge-dot`) : plein, ou vide pour dire l'absence. */
@Composable
private fun StatusDot(tint: Color, filled: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(tint) else Modifier.border(1.5.dp, tint, CircleShape)),
    )
}

/** Bouton carré de 40 dp portant un pictogramme de 20 dp : les réglages de l'en-tête, le retour, la sortie de la sélection. */
@Composable
internal fun IconAction(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(ControlShape)
            .pressable(onClick = onClick),
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

/** Tuile de 36 dp sur le fond discret `muted`, avec un pictogramme de 18 dp : le type d'une ligne, le boîtier trouvé. */
@Composable
internal fun IconTile(icon: Int) {
    Box(
        modifier = Modifier.size(Tokens.ICON_TILE_DP.dp).clip(ControlShape).background(Dictee.colors.muted),
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

/** La teinte d'une pastille : un état se dit par la couleur et par le mot, jamais par la couleur seule. */
internal enum class BadgeTone { Neutral, Success, Warning, Destructive }

/**
 * Pastille d'état : un aplat teinté sans bordure, en pilule, de 26 dp au moins. [leading] dessine le pictogramme ou le point devant le
 * libellé avec la couleur du texte. Avec [onClick], toute la pastille répond au toucher.
 */
@Composable
internal fun Badge(
    text: String,
    tone: BadgeTone = BadgeTone.Neutral,
    onClick: (() -> Unit)? = null,
    leading: (@Composable (tint: Color) -> Unit)? = null,
) {
    val colors = Dictee.colors
    val (container, content) = when (tone) {
        BadgeTone.Neutral -> colors.muted to colors.textSecondary
        BadgeTone.Success -> colors.successSurface to colors.success
        BadgeTone.Warning -> colors.warningSurface to colors.warning
        BadgeTone.Destructive -> colors.destructiveSurface to colors.destructive
    }
    Row(
        modifier = Modifier
            .heightIn(min = Tokens.BADGE_HEIGHT_DP.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (onClick != null) Modifier.pressable(Tokens.RADIUS_PILL_DP.dp, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke(content)
        Text(text = text, style = Dictee.type.captionStrong, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pastille de l'état d'un enregistrement : réussite pour ce qui est récupéré ou envoyé, erreur en rouge, neutre pour le reste. */
@Composable
fun StateBadge(state: DisplayState) {
    Badge(text = state.label, tone = state.tone()) { tint -> BadgeIcon(state, tint) }
}

private fun DisplayState.tone(): BadgeTone = when (this) {
    is DisplayState.Error -> BadgeTone.Destructive
    DisplayState.OnDrive, DisplayState.Ready, DisplayState.Recovered -> BadgeTone.Success
    is DisplayState.Recovering, DisplayState.Sending, is DisplayState.Transcribing, DisplayState.OnBox -> BadgeTone.Neutral
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
        is DisplayState.Transcribing -> state.percent?.let {
            CircularProgressIndicator(
                progress = { it / 100f }, modifier = Modifier.size(12.dp), color = tint, strokeWidth = 2.dp, trackColor = Color.Transparent,
            )
        } ?: CircularProgressIndicator(modifier = Modifier.size(12.dp), color = tint, strokeWidth = 2.dp)
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

/** Le rôle d'un bouton : `button-primary`, `button` (secondaire) ou `button-danger`. */
private enum class ButtonTone { Primary, Secondary, Danger }

/** Bouton principal : fond `primary`, texte `primary-foreground`. [icon] est un pictogramme de 18 dp devant le texte. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) =
    ButtonBox(text, ButtonTone.Primary, enabled, modifier, icon, compact = false, onClick)

/** Bouton secondaire : un aplat gris sans bordure. [icon] est un pictogramme de 18 dp devant le texte. */
@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, icon: Int? = null, mono: Boolean = false, onClick: () -> Unit) =
    ButtonBox(text, ButtonTone.Secondary, enabled, modifier, icon, compact = false, onClick, mono)

/** Bouton d'une action qui supprime : texte `destructive` sur `destructive-surface`. */
@Composable
internal fun DangerButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    ButtonBox(text, ButtonTone.Danger, enabled, modifier, icon = null, compact = false, onClick)

/** Petit bouton de 36 dp : « Associer » en plein (`primary`), « Tout » en gris. Inactif ([enabled] faux), il passe à `opacity-disabled`. */
@Composable
internal fun CompactButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    ButtonBox(text, if (primary) ButtonTone.Primary else ButtonTone.Secondary, enabled, Modifier, icon = null, compact = true, onClick)

/**
 * Un aplat sans bordure de 44 dp (36 en [compact], en `control-sm`). Un bouton pressé prend la teinte du survol du design system et se réduit
 * à 98 % ; désactivé, il garde son aspect à `opacity-disabled`. Un bouton compact garde une zone tactile de `touch-target` (48 dp).
 */
@Composable
private fun ButtonBox(
    text: String,
    tone: ButtonTone,
    enabled: Boolean,
    modifier: Modifier,
    icon: Int?,
    compact: Boolean,
    onClick: () -> Unit,
    mono: Boolean = false,
) {
    val colors = Dictee.colors
    val (rest, pressedFill, content) = when (tone) {
        ButtonTone.Primary -> Triple(colors.primary, colors.primaryPressed, colors.onPrimary)
        ButtonTone.Secondary -> Triple(colors.secondary, colors.secondaryPressed, colors.text)
        ButtonTone.Danger -> Triple(colors.destructiveSurface, colors.destructivePressed, colors.destructive)
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(durationMillis = 120, easing = StackEasing), label = "bouton pressé")
    Box(
        modifier = modifier
            .then(if (compact) Modifier.minimumInteractiveComponentSize() else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else Tokens.DISABLED_ALPHA
            }
            .heightIn(min = (if (compact) Tokens.BUTTON_HEIGHT_COMPACT_DP else Tokens.BUTTON_HEIGHT_DP).dp)
            .clip(ControlShape)
            .background(if (pressed) pressedFill else rest)
            .focusRing(source, Tokens.RADIUS_DP.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
            }
            Text(text = text, style = if (mono) Dictee.type.mono else if (compact) Dictee.type.controlSm else Dictee.type.control, color = content, maxLines = 1)
        }
    }
}

/** Une ligne et sa barre fine par traitement en cours ; rien ne s'affiche sans récupération ni envoi. */
@Composable
fun ProgressBars(recovery: Progress?, send: Progress?, transcription: Progress? = null) {
    if (recovery == null && send == null && transcription == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        recovery?.let { ProgressLine(it) }
        send?.let { ProgressLine(it) }
        transcription?.let { ProgressLine(it) }
    }
}

@Composable
private fun ProgressLine(progress: Progress) {
    val colors = Dictee.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = progress.label(), style = Dictee.type.caption, color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Tokens.PROGRESS_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.border),
        ) {
            if (progress.percent == Progress.UNKNOWN) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(), color = colors.primary, trackColor = Color.Transparent,
                )
            } else Box(
                modifier = Modifier
                    .fillMaxWidth(progress.percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
    }
}

/** Titre de section en capitales (`overline`), comme les jours de la liste et les rubriques des réglages. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.FRENCH),
        style = Dictee.type.overline,
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
            style = Dictee.type.titleSection,
            color = Dictee.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** La teinte d'un encart : neutre pour une aide, destructive pour une erreur. */
internal enum class NoticeTone { Neutral, Destructive }

/**
 * Encart dans le flux de l'écran (`notice`) : un aplat de `radius-lg`, un pictogramme, un texte, puis une [action] facultative. Un encart
 * d'erreur prend un trait de sa teinte à 28 %. Une action prend le gris du bouton secondaire, qui reste lisible sur une teinte.
 */
@Composable
internal fun Notice(text: String, tone: NoticeTone = NoticeTone.Neutral, action: (@Composable () -> Unit)? = null) {
    val colors = Dictee.colors
    val destructive = tone == NoticeTone.Destructive
    val tint = if (destructive) colors.destructive else colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ElementShape)
            .background(if (destructive) colors.destructiveSurface else colors.muted)
            .then(if (destructive) Modifier.border(1.dp, colors.destructive.copy(alpha = 0.28f), ElementShape) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = if (action == null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.padding(top = if (action == null) 2.dp else 0.dp).size(18.dp),
            tint = tint,
        )
        Text(text = text, style = Dictee.type.bodySm, color = colors.text, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * Champ de saisie sur une ligne : un aplat `field-bg` de 44 dp au moins, un fin contour `input`. Au focus, l'aplat s'éclaircit et le
 * contour passe à 2 dp en `primary` ; le contenu ne bouge pas, le padding perd ce que la bordure gagne.
 */
@Composable
internal fun DicteeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = Dictee.colors
    val source = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = Dictee.type.body.copy(color = colors.text),
        singleLine = true,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = source,
        decorationBox = { field -> DicteeFieldDecoration(source, enabled, Tokens.BUTTON_HEIGHT_DP.dp, Alignment.CenterStart, field) },
    )
}

/** Éditeur multiligne avec sélection conservée pour l'insertion des variables. */
@Composable
internal fun DicteeTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = Tokens.BUTTON_HEIGHT_DP.dp,
) {
    val colors = Dictee.colors
    val source = remember { MutableInteractionSource() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = Dictee.type.body.copy(color = colors.text),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = source,
        decorationBox = { field -> DicteeFieldDecoration(source, enabled, minHeight, Alignment.TopStart, field) },
    )
}

@Composable
private fun DicteeFieldDecoration(source: MutableInteractionSource, enabled: Boolean, minHeight: Dp, alignment: Alignment, field: @Composable () -> Unit) {
    val colors = Dictee.colors
    val focused by source.collectIsFocusedAsState()
    val edge = if (focused) 2.dp else 1.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else Tokens.DISABLED_ALPHA }
            .heightIn(min = minHeight)
            .clip(ControlShape)
            .background(if (focused) colors.fieldFocused else colors.field)
            .border(edge, if (focused) colors.primary else colors.input, ControlShape)
            .padding(horizontal = 15.dp - edge, vertical = 11.dp - edge),
        contentAlignment = alignment,
    ) { field() }
}

/**
 * Filtre en pilule de 36 dp : l'aplat gris du bouton secondaire, ou `primary` quand il est choisi. [count] montre le nombre d'éléments
 * que le filtre garde, ou rien s'il est nul.
 */
@Composable
internal fun FilterPill(label: String, selected: Boolean, count: Int? = null, onClick: () -> Unit) {
    val colors = Dictee.colors
    Row(
        modifier = Modifier
            .height(Tokens.BUTTON_HEIGHT_COMPACT_DP.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.secondary)
            .pressable(Tokens.RADIUS_PILL_DP.dp, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, style = Dictee.type.label, color = if (selected) colors.onPrimary else colors.text, maxLines = 1)
        if (count != null) {
            Text(
                text = "$count",
                style = Dictee.type.label,
                color = if (selected) colors.onPrimary.copy(alpha = 0.7f) else colors.textSecondary,
            )
        }
    }
}
