package top.supergeoff.dictee.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Rayon des boutons, des champs et des tuiles, des lignes et des encarts, puis des cartes : ceux de [Tokens]. */
internal val ControlShape = RoundedCornerShape(Tokens.RADIUS_DP.dp)
internal val ElementShape = RoundedCornerShape(Tokens.ELEMENT_RADIUS_DP.dp)
internal val CardShape = RoundedCornerShape(Tokens.CARD_RADIUS_DP.dp)

/**
 * `shadow-sm` : l'ombre douce d'une carte au repos, en clair seulement. Compose n'a pas d'ombre à deux couches, d'où une élévation de
 * 1 dp teintée du noir du texte, à la place de `0 1px 2px` et `0 1px 1px`. Avec [elevation] à 2 dp et [lightOnly] faux, c'est
 * `shadow-pressed` (l'option choisie d'un choix segmenté), qui existe aussi en sombre, avec un noir plus dense.
 */
@Composable
internal fun Modifier.softShadow(shape: Shape, elevation: Dp = 1.dp, lightOnly: Boolean = true): Modifier {
    if (Dictee.dark && lightOnly) return this
    val tint = if (Dictee.dark) Color.Black.copy(alpha = 0.5f) else Dictee.colors.text.copy(alpha = 0.14f)
    return shadow(elevation, shape, clip = false, ambientColor = tint, spotColor = tint)
}

/** Une carte : surface, contour `border` et ombre douce en clair. Son contenu est rogné aux coins arrondis. */
@Composable
internal fun Modifier.card(shape: Shape = CardShape): Modifier =
    softShadow(shape).clip(shape).background(Dictee.colors.surface).border(1.dp, Dictee.colors.border, shape)

/**
 * Anneau de focus clavier : 2 dp pleins en `focus`. Il se dessine dans les bords de l'élément et non à 2 dp au-delà comme sur le web,
 * car un parent qui rogne (une carte, une liste) le couperait.
 */
@Composable
internal fun Modifier.focusRing(source: InteractionSource, radius: Dp): Modifier {
    val focused by source.collectIsFocusedAsState()
    val color = Dictee.colors.focus
    if (!focused) return this
    return drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((radius.toPx() - stroke / 2).coerceAtLeast(0f)),
            style = Stroke(stroke),
        )
    }
}

/**
 * Zone qui répond au toucher : l'indication de material3 (un survol n'existe pas sur mobile, l'état pressé le remplace) et l'anneau de
 * focus. [radius] arrondit l'anneau, et doit valoir celui du `clip` qui précède.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.pressable(
    radius: Dp = Tokens.RADIUS_DP.dp,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return focusRing(source, radius).combinedClickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
