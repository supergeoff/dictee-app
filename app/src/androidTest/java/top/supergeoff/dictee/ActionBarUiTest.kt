package top.supergeoff.dictee

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.ActionBarButtons
import top.supergeoff.dictee.ui.DicteeTheme

class ActionBarUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelsAndCountsFitPhoneWidthInNormalAndSelectionStates() {
        val counts = androidx.compose.runtime.mutableStateOf(Triple(0, 2, 6))
        compose.setContent {
            val (recover, transcribe, send) = counts.value
            DicteeTheme(false) {
                androidx.compose.foundation.layout.Box(Modifier.width(400.dp)) {
                    ActionBarButtons("Récupérer ($recover)", recover > 0, transcribe, "Envoyer ($send)", send > 0, true, {}, {}, {})
                }
            }
        }
        listOf(Triple(0, 2, 6), Triple(1, 0, 1)).forEach { (recover, transcribe, send) ->
            compose.runOnIdle { counts.value = Triple(recover, transcribe, send) }
            listOf("Récupérer ($recover)", "VTT + MD ($transcribe)", "Envoyer ($send)").forEach { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("No text layout for $label", layouts.isNotEmpty())
                assertTrue("Missing text: $label", layouts.single().getLineEnd(0, visibleEnd = true) == label.length)
                assertTrue("Clipped text: $label", layouts.single().getLineRight(0) <= layouts.single().size.width)
            }
        }
    }
}
