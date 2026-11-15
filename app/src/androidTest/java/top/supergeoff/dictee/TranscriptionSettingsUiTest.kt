package top.supergeoff.dictee

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import top.supergeoff.dictee.ui.DicteeTheme
import top.supergeoff.dictee.ui.TranscriptionSection

class TranscriptionSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() = scope.cancel()

    @Test fun storedKeyIsNeverLoadedAndCanBeReplaced() {
        val form = TranscriptionSettingsForm(scope) { _, _ -> }
        val local = LocalState(transcriptionSettings = TranscriptionSettings(credentialRef = "synthetic-stored-secret"))
        compose.setContent { DicteeTheme(false) { Column(Modifier.verticalScroll(rememberScrollState())) { TranscriptionSection(local, form) } } }
        compose.onNodeWithText("••••••••").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic-stored-secret", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("transcription-secret").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("replacement")
        compose.onNodeWithText("••••••••").assertDoesNotExist()
    }
}
