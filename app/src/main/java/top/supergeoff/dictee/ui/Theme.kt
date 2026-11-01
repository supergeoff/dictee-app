package top.supergeoff.dictee.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.supergeoff.dictee.R

/** Les couleurs de [Palette] converties en `Color`, lues par l'interface avec [Dictee.colors]. */
@Immutable
class DicteeColors(
    val background: Color, val surface: Color, val text: Color, val textSecondary: Color, val border: Color,
    val primary: Color, val onPrimary: Color,
    val reviewBackground: Color, val reviewText: Color, val errorBackground: Color, val errorText: Color,
)

/** Les styles de texte, sans couleur : l'appelant la prend dans [Dictee.colors]. */
@Immutable
class DicteeType(val body: TextStyle, val label: TextStyle, val title: TextStyle, val mono: TextStyle)

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private val DefaultType = DicteeType(
    body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    mono = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
)

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
 * Fournit les tokens et règle aussi un `MaterialTheme` construit sur eux : la feuille du bas, les interrupteurs
 * et les dialogues de material3 reprennent ainsi les couleurs du design system.
 */
@Composable
fun DicteeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = remember(dark) { (if (dark) Tokens.dark else Tokens.light).toColors() }
    val scheme = remember(dark) { colors.toMaterial(dark) }
    CompositionLocalProvider(
        LocalDicteeColors provides colors,
        LocalDicteeDark provides dark,
        LocalDicteeType provides DefaultType,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private fun Palette.toColors() = DicteeColors(
    background = Color(background), surface = Color(surface), text = Color(text),
    textSecondary = Color(textSecondary), border = Color(border),
    primary = Color(primary), onPrimary = Color(onPrimary),
    reviewBackground = Color(reviewBackground), reviewText = Color(reviewText),
    errorBackground = Color(errorBackground), errorText = Color(errorText),
)

/**
 * Rôles material3 utilisés par la feuille du bas (conteneur), les interrupteurs (piste, pastille, contour)
 * et les dialogues (conteneur, textes, boutons). La piste d'un interrupteur éteint prend le fond de l'écran
 * pour se détacher de la surface. `surfaceTint` reprend la surface : material3 ne teinte un conteneur que si sa
 * couleur égale `surface`, et mélanger la surface avec elle-même ne change rien, donc plus de teinte violette.
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
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = background,
    )
