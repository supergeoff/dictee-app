package top.supergeoff.dictee.ui

/**
 * Les couleurs d'un thème de Stack UI, en ARGB sur 32 bits (`0xAARRGGBB`). Chaque champ reprend un jeton de `tokens.json`,
 * par son nom court quand l'app en avait un (`surface` pour `card`, `text` pour `foreground`, `textSecondary` pour `muted-foreground`,
 * `onPrimary` pour `primary-foreground`) ; l'état pressé (`...Pressed`) tient la place du survol, qui n'existe pas sur mobile.
 * Kotlin pur : la conversion en `Color` se fait dans le thème, ce qui garde les valeurs testables sans Compose.
 * Attention : le CSS écrit un alpha en dernier (`#RRGGBBAA`), Android en premier (`0xAARRGGBB`).
 */
data class Palette(
    val background: Long, val surface: Long, val text: Long, val textSecondary: Long,
    val border: Long, val input: Long, val muted: Long, val accent: Long,
    val primary: Long, val onPrimary: Long, val primaryPressed: Long,
    val secondary: Long, val secondaryPressed: Long,
    val field: Long, val fieldPressed: Long, val fieldFocused: Long,
    val success: Long, val successSurface: Long,
    /** Réservé à l'état « À relire » du lot 4 : aucun écran ne l'affiche encore. */
    val warning: Long, val warningSurface: Long,
    val destructive: Long, val destructiveSurface: Long, val destructivePressed: Long,
    val focus: Long, val overlay: Long,
)

/** Un style de texte de `tokens.json` : taille en sp, interligne en multiple de la taille, graisse CSS, approche en em. */
data class TypeSpec(val sizeSp: Int, val lineHeight: Double, val weight: Int, val letterSpacingEm: Double = 0.0)

/** L'échelle typographique de Stack UI. La police est Figtree pour le texte et JetBrains Mono pour `mono` : voir `Theme.kt`. */
object TypeScale {
    val titlePage = TypeSpec(32, 1.1, 700, -0.02)
    val titleModal = TypeSpec(22, 1.25, 700, -0.01)
    val titleSection = TypeSpec(18, 1.3, 700, -0.01)
    val titleItem = TypeSpec(16, 1.3, 700)
    val titleGroup = TypeSpec(15, 1.3, 700)
    val body = TypeSpec(15, 1.5, 400)
    val control = TypeSpec(15, 1.1, 600)
    val label = TypeSpec(14, 1.4, 600)
    val caption = TypeSpec(13, 1.45, 500)
    val captionStrong = TypeSpec(12, 1.2, 600)
    val overline = TypeSpec(12, 1.2, 700, 0.06)
    val wordmark = TypeSpec(16, 1.25, 700, 0.08)
    val mono = TypeSpec(13, 1.55, 400)

    /** Les styles par leur nom dans `tokens.json`, pour le test de dérive. */
    val all: Map<String, TypeSpec> = mapOf(
        "title-page" to titlePage, "title-modal" to titleModal, "title-section" to titleSection, "title-item" to titleItem,
        "title-group" to titleGroup, "body" to body, "control" to control, "label" to label, "caption" to caption,
        "caption-strong" to captionStrong, "overline" to overline, "wordmark" to wordmark, "mono" to mono,
    )
}

/** Les jetons de Stack UI : les deux palettes, les rayons, les hauteurs et les opacités. Les dp valent les px du design system. */
object Tokens {
    val light = Palette(
        background = 0xFFFFFFFF, surface = 0xFFFFFFFF, text = 0xFF0B0B0B, textSecondary = 0xFF5C5C5C,
        border = 0xFFE2E2E2, input = 0xFFE2E2E2, muted = 0xFFF3F3F3, accent = 0xFFECECEC,
        primary = 0xFF0B0B0B, onPrimary = 0xFFFFFFFF, primaryPressed = 0xFF2F2F2F,
        secondary = 0xFFECECEC, secondaryPressed = 0xFFE2E2E2,
        field = 0xFFECECEC, fieldPressed = 0xFFE2E2E2, fieldFocused = 0xFFFFFFFF,
        success = 0xFF05713A, successSurface = 0xFFDCF3E5,
        warning = 0xFF7C4A00, warningSurface = 0xFFFFF1CC,
        destructive = 0xFFB3130B, destructiveSurface = 0xFFFDE4E1, destructivePressed = 0xFFFBD2CD,
        focus = 0xFF1F5FE0, overlay = 0x800B0B0B,
    )

    /** Les surfaces d'état sont translucides en sombre (`#4cc38a29` en CSS) : elles se composent avec la surface dessous. */
    val dark = Palette(
        background = 0xFF0B0B0B, surface = 0xFF151515, text = 0xFFECECEC, textSecondary = 0xFFA3A3A3,
        border = 0x1FFFFFFF, input = 0x26FFFFFF, muted = 0xFF1C1C1C, accent = 0xFF2F2F2F,
        primary = 0xFFF3F3F3, onPrimary = 0xFF0B0B0B, primaryPressed = 0xFFFFFFFF,
        secondary = 0xFF242424, secondaryPressed = 0xFF2F2F2F,
        field = 0xFF242424, fieldPressed = 0xFF2F2F2F, fieldFocused = 0xFF151515,
        success = 0xFF4CC38A, successSurface = 0x294CC38A,
        warning = 0xFFF5B83D, warningSurface = 0x29F5B83D,
        destructive = 0xFFFF7B72, destructiveSurface = 0x29FF7B72, destructivePressed = 0x40FF7B72,
        focus = 0xFF6C9BFF, overlay = 0xB8000000,
    )

    /** `radius-sm` : le coin d'une case à cocher. */
    const val RADIUS_SM_DP = 6

    /** `radius-inner` : une option posée dans un conteneur de [ELEMENT_RADIUS_DP], comme celles du choix du thème. */
    const val RADIUS_INNER_DP = 8

    /** `radius-md` : boutons, champs, tuiles. */
    const val RADIUS_DP = 10

    /** `radius-lg` : lignes, encarts, menus. */
    const val ELEMENT_RADIUS_DP = 12

    /** `radius-xl` : cartes, dialogues, feuille du bas. */
    const val CARD_RADIUS_DP = 16

    /** `radius-pill` : pastilles et boutons en pilule. */
    const val RADIUS_PILL_DP = 999

    /** `control-height` : boutons et champs. */
    const val BUTTON_HEIGHT_DP = 44

    /** `control-height-sm` : bouton compact. */
    const val BUTTON_HEIGHT_COMPACT_DP = 36

    /** `badge-height` : hauteur minimale d'une pastille. */
    const val BADGE_HEIGHT_DP = 26

    /** `opacity-disabled` : un bouton désactivé garde son aspect à cette opacité. */
    const val DISABLED_ALPHA = 0.4f

    /** `opacity-inactive` : une ligne dont l'objet ne répond plus. */
    const val INACTIVE_ALPHA = 0.55f
}
