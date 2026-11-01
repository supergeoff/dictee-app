package top.supergeoff.dictee.ui

/**
 * Les couleurs d'un thème du design system, en ARGB sur 32 bits (`0xAARRGGBB`).
 * Kotlin pur : la conversion en `Color` se fait dans le thème, ce qui garde les valeurs testables sans Compose.
 */
data class Palette(
    val background: Long, val surface: Long, val text: Long, val textSecondary: Long, val border: Long,
    val primary: Long, val onPrimary: Long,
    val reviewBackground: Long, val reviewText: Long, val errorBackground: Long, val errorText: Long,
)

/** Les tokens de la spec : les deux palettes, les rayons et la hauteur des boutons. */
object Tokens {
    val light = Palette(
        background = 0xFFF5F5F5, surface = 0xFFFFFFFF, text = 0xFF0A0A0A, textSecondary = 0xFF6B6B6B, border = 0xFFD7D7D7,
        primary = 0xFF171717, onPrimary = 0xFFFAFAFA,
        reviewBackground = 0xFFFEF3C7, reviewText = 0xFF92400E, errorBackground = 0xFFFEE2E2, errorText = 0xFF991B1B,
    )

    /** Les fonds « ambre » et « rouge » sont à 50 % d'opacité, comme dans la maquette : `rgba(120,53,15,0.5)` et `rgba(127,29,29,0.5)`. */
    val dark = Palette(
        background = 0xFF0D0D0D, surface = 0xFF171717, text = 0xFFFAFAFA, textSecondary = 0xFFB3B3B3, border = 0x1AFFFFFF,
        primary = 0xFFE5E5E5, onPrimary = 0xFF171717,
        reviewBackground = 0x8078350F, reviewText = 0xFFFBBF24, errorBackground = 0x807F1D1D, errorText = 0xFFFCA5A5,
    )

    const val RADIUS_DP = 10
    const val CARD_RADIUS_DP = 12
    const val BUTTON_HEIGHT_DP = 48
}
