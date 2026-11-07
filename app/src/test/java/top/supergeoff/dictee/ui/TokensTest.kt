package top.supergeoff.dictee.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Garde-fou de dérive entre l'app et Stack UI. `src/test/resources/stack-ui/tokens.json` est une copie fidèle du `tokens.json`
 * du design system : pour se mettre à jour, copier de nouveau ce fichier, puis lancer les tests. Un écart de valeur, ou un jeton
 * de couleur que ni la palette ni la liste des jetons ignorés ne connaît, fait échouer le test.
 */
class TokensTest {
    private val json = JSONObject(
        checkNotNull(TokensTest::class.java.classLoader?.getResource("stack-ui/tokens.json")) { "copie de tokens.json absente" }.readText(),
    )

    private fun tokens(section: String): List<JSONObject> = json.getJSONObject(section).getJSONArray("tokens").objects()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private val colorTokens = tokens("color").associateBy { it.getString("name") }

    /** Un jeton de couleur résolu pour un thème (`light` ou `dark`), en ARGB. Le CSS écrit `#RRGGBBAA` : l'alpha passe devant. */
    private fun color(name: String, theme: String): Long {
        val raw = colorTokens.getValue(name).get("value")
        return parse(if (raw is JSONObject) raw.getString(theme) else raw as String, theme)
    }

    private fun parse(text: String, theme: String): Long = when {
        text.startsWith("{") -> color(text.trim('{', '}'), theme)
        text.startsWith("#") && text.length == 7 -> 0xFF000000 or text.substring(1).toLong(16)
        text.startsWith("#") && text.length == 9 -> (text.substring(7).toLong(16) shl 24) or text.substring(1, 7).toLong(16)
        text.startsWith("rgba(") -> {
            val (r, g, b, a) = text.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim().toDouble() }
            ((a * 255).roundToLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
        }
        else -> error("valeur de couleur inconnue : $text")
    }

    /** La palette attendue, lue dans tokens.json. Un champ ajouté à [Palette] oblige à le renseigner ici. */
    private fun expected(theme: String): Palette {
        fun c(name: String) = color(name, theme)
        return Palette(
            background = c("background"), surface = c("card"), text = c("foreground"), textSecondary = c("muted-foreground"),
            border = c("border"), input = c("input"), muted = c("muted"), accent = c("accent"),
            primary = c("primary"), onPrimary = c("primary-foreground"), primaryPressed = c("button-primary-bg-hover"),
            secondary = c("button-secondary-bg"), secondaryPressed = c("button-secondary-bg-hover"), switchOff = c("switch-bg-off"),
            field = c("field-bg"), fieldPressed = c("field-bg-hover"), fieldFocused = c("field-bg-focus"),
            success = c("success"), successSurface = c("success-surface"),
            warning = c("warning"), warningSurface = c("warning-surface"),
            destructive = c("destructive"), destructiveSurface = c("destructive-surface"), destructivePressed = c("button-danger-bg-hover"),
            focus = c("focus"), overlay = c("overlay"),
        )
    }

    private fun hex(value: Long) = "#%08x".format(value)

    private fun assertPalette(theme: String, actual: Palette) {
        val wanted = expected(theme)
        Palette::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.forEach {
            it.isAccessible = true
            assertEquals("${it.name} ($theme)", hex(it.get(wanted) as Long), hex(it.get(actual) as Long))
        }
    }

    @Test
    fun `la palette claire reprend les jetons de tokens json`() = assertPalette("light", Tokens.light)

    @Test
    fun `la palette sombre reprend les jetons de tokens json`() = assertPalette("dark", Tokens.dark)

    /** Les jetons que l'app ne reprend pas : l'information n'a pas d'usage dans l'app, et les alias valent un champ de la palette. */
    private val ignored = setOf("info", "info-surface")
    private val aliases = mapOf(
        "card-foreground" to "foreground", "accent-foreground" to "foreground",
        "button-primary-bg" to "primary", "button-primary-text" to "primary-foreground",
        "button-danger-bg" to "destructive-surface", "button-danger-text" to "destructive",
    )

    @Test
    fun `les jetons semantiques et de composant sont tous repris ou ignores`() {
        val known = setOf(
            "background", "card", "foreground", "muted-foreground", "border", "input", "muted", "accent", "primary", "primary-foreground",
            "button-primary-bg-hover", "button-secondary-bg", "button-secondary-bg-hover", "switch-bg-off", "field-bg", "field-bg-hover", "field-bg-focus",
            "success", "success-surface", "warning", "warning-surface", "destructive", "destructive-surface", "button-danger-bg-hover",
            "focus", "overlay",
        )
        val unhandled = colorTokens.filterValues { it.get("value") is JSONObject }.keys - known - ignored - aliases.keys
        assertTrue("jetons de tokens.json que l'app ne connaît pas : $unhandled", unhandled.isEmpty())
        // Un alias suit toujours le jeton qu'il double, dans les deux thèmes.
        listOf("light", "dark").forEach { theme -> aliases.forEach { (alias, target) -> assertEquals("$alias ($theme)", color(target, theme), color(alias, theme)) } }
    }

    @Test
    fun `les valeurs translucides sont lues en ARGB`() {
        assertEquals(0x1FFFFFFFL, Tokens.dark.border)
        assertEquals(0x26FFFFFFL, Tokens.dark.input)
        assertEquals(0x294CC38AL, Tokens.dark.successSurface)
        assertEquals(0x40FF7B72L, Tokens.dark.destructivePressed)
        assertEquals(0x800B0B0BL, Tokens.light.overlay)
        assertEquals(0xB8000000L, Tokens.dark.overlay)
    }

    private fun px(text: String) = text.removeSuffix("px").toInt()

    @Test
    fun `les rayons, les hauteurs et les opacites reprennent tokens json`() {
        val radius = tokens("radius").associate { it.getString("name") to it.getString("value") }
        assertEquals(px(radius.getValue("radius-sm")), Tokens.RADIUS_SM_DP)
        assertEquals(px(radius.getValue("radius-inner")), Tokens.RADIUS_INNER_DP)
        assertEquals(px(radius.getValue("radius-md")), Tokens.RADIUS_DP)
        assertEquals(px(radius.getValue("radius-lg")), Tokens.ELEMENT_RADIUS_DP)
        assertEquals(px(radius.getValue("radius-xl")), Tokens.CARD_RADIUS_DP)
        assertEquals(px(radius.getValue("radius-pill")), Tokens.RADIUS_PILL_DP)
        val layout = tokens("layout").associate { it.getString("name") to it.getString("value") }
        assertEquals(px(layout.getValue("control-height")), Tokens.BUTTON_HEIGHT_DP)
        assertEquals(px(layout.getValue("control-height-sm")), Tokens.BUTTON_HEIGHT_COMPACT_DP)
        assertEquals(px(layout.getValue("badge-height")), Tokens.BADGE_HEIGHT_DP)
        assertEquals(px(layout.getValue("touch-target")), Tokens.TOUCH_TARGET_DP)
        assertEquals(px(layout.getValue("icon-tile-size")), Tokens.ICON_TILE_DP)
        assertEquals(px(layout.getValue("progress-height")), Tokens.PROGRESS_HEIGHT_DP)
        assertEquals(px(layout.getValue("switch-width")), Tokens.SWITCH_WIDTH_DP)
        assertEquals(px(layout.getValue("switch-height")), Tokens.SWITCH_HEIGHT_DP)
        val opacity = tokens("opacity").associate { it.getString("name") to it.getString("value").toFloat() }
        assertEquals(opacity.getValue("opacity-disabled"), Tokens.DISABLED_ALPHA, 0f)
        assertEquals(opacity.getValue("opacity-inactive"), Tokens.INACTIVE_ALPHA, 0f)
    }

    @Test
    fun `l'echelle typographique reprend tokens json`() {
        val styles = json.getJSONObject("type").getJSONArray("groups").objects()
            .flatMap { it.getJSONArray("styles").objects() }.associateBy { it.getString("name") }
        val local = TypeScale.all
        assertEquals("styles de tokens.json absents de l'app ou en trop", styles.keys, local.keys)
        styles.forEach { (name, style) ->
            val spec = local.getValue(name)
            assertEquals("$name : taille", px(style.getString("fontSize")), spec.sizeSp)
            assertEquals("$name : interligne", style.getDouble("lineHeight"), spec.lineHeight, 1e-9)
            assertEquals("$name : graisse", style.getInt("fontWeight"), spec.weight)
            val spacing = style.optString("letterSpacing", "0em").removeSuffix("em").toDouble()
            assertEquals("$name : approche", spacing, spec.letterSpacingEm, 1e-9)
        }
    }

    // Contraste WCAG ---------------------------------------------------------------------------------------------

    /** [top] posée sur [base] : un fond translucide se compose avec ce qu'il y a dessous. */
    private fun over(top: Long, base: Long): Long {
        val a = (top ushr 24 and 0xFF) / 255.0
        fun channel(shift: Int) = ((((top shr shift) and 0xFF) * a + ((base shr shift) and 0xFF) * (1 - a))).roundToLong()
        return 0xFF000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun luminance(argb: Long): Double {
        fun lin(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `les textes tiennent 4,5 pour 1 sur leur fond dans les deux themes`() {
        listOf("clair" to Tokens.light, "sombre" to Tokens.dark).forEach { (theme, p) ->
            val pages = listOf(p.background, p.surface, p.muted, p.accent, p.field, p.secondary)
            pages.forEach { base ->
                assertTrue("texte sur ${hex(base)} ($theme)", contrast(p.text, base) >= 4.5)
                assertTrue("texte secondaire sur ${hex(base)} ($theme)", contrast(p.textSecondary, base) >= 4.5)
            }
            assertTrue("texte du bouton principal ($theme)", contrast(p.onPrimary, p.primary) >= 4.5)
            assertTrue("texte du bouton principal pressé ($theme)", contrast(p.onPrimary, p.primaryPressed) >= 4.5)
            listOf("succès" to (p.success to p.successSurface), "avertissement" to (p.warning to p.warningSurface), "erreur" to (p.destructive to p.destructiveSurface)).forEach { (tone, pair) ->
                listOf(p.surface, p.background).forEach { under ->
                    assertTrue("$tone sur sa surface ($theme)", contrast(pair.first, over(pair.second, under)) >= 4.5)
                }
            }
            assertTrue("texte d'erreur sur le bouton danger pressé ($theme)", contrast(p.destructive, over(p.destructivePressed, p.surface)) >= 4.5)
            assertTrue("anneau de focus sur le fond ($theme)", contrast(p.focus, p.background) >= 3.0)
            assertTrue("piste d'interrupteur éteint sur le fond ($theme)", contrast(p.switchOff, p.background) >= 3.0)
            assertTrue("pastille d'interrupteur sur la piste éteinte ($theme)", contrast(p.surface, p.switchOff) >= 3.0)
            assertTrue("pastille d'interrupteur sur la piste allumée ($theme)", contrast(p.surface, p.primary) >= 3.0)
        }
    }
}
