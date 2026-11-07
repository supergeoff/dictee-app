package top.supergeoff.dictee.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import top.supergeoff.dictee.R

/** Les couleurs de [Palette] converties en `Color`, lues par l'interface avec [Dictee.colors]. Les champs portent les mêmes noms que ceux de [Palette]. */
@Immutable
class DicteeColors(palette: Palette) {
    val background = Color(palette.background)
    val surface = Color(palette.surface)
    val text = Color(palette.text)
    val textSecondary = Color(palette.textSecondary)
    val border = Color(palette.border)
    val input = Color(palette.input)
    val muted = Color(palette.muted)
    val accent = Color(palette.accent)
    val primary = Color(palette.primary)
    val onPrimary = Color(palette.onPrimary)
    val primaryPressed = Color(palette.primaryPressed)
    val secondary = Color(palette.secondary)
    val secondaryPressed = Color(palette.secondaryPressed)
    val field = Color(palette.field)
    val fieldPressed = Color(palette.fieldPressed)
    val fieldFocused = Color(palette.fieldFocused)
    val success = Color(palette.success)
    val successSurface = Color(palette.successSurface)
    val warning = Color(palette.warning)
    val warningSurface = Color(palette.warningSurface)
    val destructive = Color(palette.destructive)
    val destructiveSurface = Color(palette.destructiveSurface)
    val destructivePressed = Color(palette.destructivePressed)
    val focus = Color(palette.focus)
    val overlay = Color(palette.overlay)
}

/**
 * Les styles de texte de Stack UI, sans couleur : l'appelant la prend dans [Dictee.colors]. Ceux de [TypeScale] y sont tous.
 * [bodySmall] et [rowTitle] sont propres à l'app : le design system n'a ni texte de 14 sp en graisse 400 (encarts), ni titre de ligne en 500.
 */
@Immutable
class DicteeType(
    val titlePage: TextStyle, val titleModal: TextStyle, val titleSection: TextStyle, val titleItem: TextStyle, val titleGroup: TextStyle,
    val body: TextStyle, val bodySmall: TextStyle, val rowTitle: TextStyle, val control: TextStyle, val label: TextStyle,
    val caption: TextStyle, val captionStrong: TextStyle, val overline: TextStyle, val wordmark: TextStyle, val mono: TextStyle,
)

/**
 * Figtree est une police variable (axe de graisse de 300 à 900) dont l'instance par défaut est la Light : chaque graisse demande son réglage
 * d'axe, sans quoi tout s'afficherait en 300. Le `minSdk` 31 dépasse l'API 26 que demandent les polices variables.
 */
@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    resId = R.font.figtree_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private fun TypeSpec.style(family: FontFamily) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * lineHeight).sp,
    letterSpacing = if (letterSpacingEm == 0.0) TextUnit.Unspecified else letterSpacingEm.em,
)

private val DefaultType = DicteeType(
    titlePage = TypeScale.titlePage.style(Figtree),
    titleModal = TypeScale.titleModal.style(Figtree),
    titleSection = TypeScale.titleSection.style(Figtree),
    titleItem = TypeScale.titleItem.style(Figtree),
    titleGroup = TypeScale.titleGroup.style(Figtree),
    body = TypeScale.body.style(Figtree),
    bodySmall = TypeScale.body.style(Figtree).copy(fontSize = 14.sp, lineHeight = 21.sp),
    rowTitle = TypeScale.body.style(Figtree).copy(fontWeight = FontWeight.Medium),
    control = TypeScale.control.style(Figtree),
    label = TypeScale.label.style(Figtree),
    caption = TypeScale.caption.style(Figtree),
    captionStrong = TypeScale.captionStrong.style(Figtree),
    overline = TypeScale.overline.style(Figtree),
    wordmark = TypeScale.wordmark.style(Figtree),
    mono = TypeScale.mono.style(JetBrainsMono),
)

/** La courbe de tous les mouvements de Stack UI : `cubic-bezier(0.2, 0, 0, 1)`. */
internal val StackEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val LocalDicteeColors = staticCompositionLocalOf<DicteeColors> { error("DicteeTheme absent de la composition") }
private val LocalDicteeDark = staticCompositionLocalOf<Boolean> { error("DicteeTheme absent de la composition") }
private val LocalDicteeType = staticCompositionLocalOf<DicteeType> { error("DicteeTheme absent de la composition") }

/** Point d'entrée de l'interface vers le thème : couleurs, mode sombre et styles de texte. */
object Dictee {
    val colors: DicteeColors @Composable @ReadOnlyComposable get() = LocalDicteeColors.current
    val dark: Boolean @Composable @ReadOnlyComposable get() = LocalDicteeDark.current
    val type: DicteeType @Composable @ReadOnlyComposable get() = LocalDicteeType.current
}

/**
 * Fournit les tokens et règle aussi un `MaterialTheme` construit sur eux : la feuille du bas, le menu et les dialogues de material3
 * reprennent ainsi les couleurs du design system.
 */
@Composable
fun DicteeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = remember(dark) { DicteeColors(if (dark) Tokens.dark else Tokens.light) }
    val scheme = remember(dark) { colors.toMaterial(dark) }
    CompositionLocalProvider(
        LocalDicteeColors provides colors,
        LocalDicteeDark provides dark,
        LocalDicteeType provides DefaultType,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Rôles material3 utilisés par la feuille du bas et le menu (conteneur), les dialogues (conteneur, textes) et l'indicateur de
 * rafraîchissement. `surfaceTint` reprend la surface : material3 ne teinte un conteneur que si sa couleur égale `surface`, et mélanger la
 * surface avec elle-même ne change rien, donc plus de teinte violette. Le voile d'une feuille est `overlay` : voir `AssociateSheet`.
 */
private fun DicteeColors.toMaterial(dark: Boolean): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = onPrimary,
        background = background,
        onBackground = text,
        surface = surface,
        surfaceTint = surface,
        onSurface = text,
        onSurfaceVariant = textSecondary,
        outline = textSecondary,
        outlineVariant = border,
        scrim = overlay,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = muted,
    )
