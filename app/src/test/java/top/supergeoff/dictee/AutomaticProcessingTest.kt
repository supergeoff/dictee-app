package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AutomaticProcessingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val folderA = "content://synthetic/tree-a"
    private val folderB = "content://synthetic/tree-b"

    @Test fun disabledOptionDoesNotCreateAnIntent() {
        val state = LocalState(autoProcessAfterRecovery = false)
            .beginAutomaticRecovery("recording", folderA, "request")
        assertTrue(state.automaticProcessingRequests.isEmpty())
    }

    @Test fun enabledOptionCapturesIntentAndLaterDisableDoesNotPreventReady() {
        val began = LocalState(autoProcessAfterRecovery = true)
            .beginAutomaticRecovery("recording", folderA, "request")
        val disabled = began.copy(autoProcessAfterRecovery = false)
        val finished = disabled.finishAutomaticRecovery(RecoveryResult.Ok("recording", "recording.mp3", 10), folderA)
        assertEquals(listOf(AutomaticProcessingRequest("request", "recording", folderA, AutomaticProcessingPhase.READY)),
            finished.automaticProcessingRequests)
    }

    @Test fun disabledAtStartDoesNotCreateIntentAfterLaterEnable() {
        val began = LocalState(autoProcessAfterRecovery = false).beginAutomaticRecovery("recording", folderA, "request")
        val enabled = began.copy(autoProcessAfterRecovery = true)
        val finished = enabled.finishAutomaticRecovery(RecoveryResult.Ok("recording", "recording.mp3", 10), folderA)
        assertTrue(finished.automaticProcessingRequests.isEmpty())
    }

    @Test fun failedRecoveryRemovesIntentAndNeverMakesItProgrammable() {
        val pending = LocalState(autoProcessAfterRecovery = true).beginAutomaticRecovery("recording", folderA, "request")
        val failed = pending.finishAutomaticRecovery(RecoveryResult.Failed("recording", "interruption", true), folderA)
        assertTrue(failed.automaticProcessingRequests.isEmpty())
    }

    @Test fun transitionsPreserveOtherFoldersAndDoNotCreateMissingIntent() {
        val other = AutomaticProcessingRequest("other", "recording", folderB, AutomaticProcessingPhase.READY)
        val state = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = listOf(other))
        val begun = state.beginAutomaticRecovery("recording", folderA, "request")
        assertEquals(listOf(other, AutomaticProcessingRequest("request", "recording", folderA, AutomaticProcessingPhase.RECOVERING)),
            begun.automaticProcessingRequests)
        assertEquals(listOf(other), state.finishAutomaticRecovery(
            RecoveryResult.Ok("absent", "absent.mp3", 10), folderA).automaticProcessingRequests)
    }

    @Test fun disabledBeginRemovesRecoveringButPreservesValidatedIntent() {
        val recovering = AutomaticProcessingRequest("recovering", "recording", folderA, AutomaticProcessingPhase.RECOVERING)
        val ready = AutomaticProcessingRequest("ready", "recording", folderA, AutomaticProcessingPhase.READY)
        val state = LocalState(autoProcessAfterRecovery = false, automaticProcessingRequests = listOf(recovering, ready))
            .beginAutomaticRecovery("recording", folderA, "new")
        assertEquals(listOf(ready), state.automaticProcessingRequests)
    }

    @Test fun beginReplacesOnlySameFileAndFolderRecoveringIntent() {
        val old = AutomaticProcessingRequest("old", "recording", folderA, AutomaticProcessingPhase.RECOVERING)
        val ready = AutomaticProcessingRequest("ready", "recording", folderA, AutomaticProcessingPhase.READY)
        val other = AutomaticProcessingRequest("other", "recording", folderB, AutomaticProcessingPhase.RECOVERING)
        val begun = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = listOf(old, ready, other))
            .beginAutomaticRecovery("recording", folderA, "new")
        assertEquals(listOf(other, AutomaticProcessingRequest("new", "recording", folderA, AutomaticProcessingPhase.RECOVERING)),
            begun.automaticProcessingRequests)
    }

    @Test fun phasesRoundTripThroughLocalStateJson() {
        val requests = AutomaticProcessingPhase.entries.mapIndexed { i, phase ->
            AutomaticProcessingRequest("id-$i", "recording-$i", folderA, phase, if (phase == AutomaticProcessingPhase.WAITING_SETTINGS) "settings" else null)
        }
        val store = LocalStateStore(tmp.newFile("state.json"))
        val state = LocalState(autoProcessAfterRecovery = true, automaticProcessingRequests = requests)
        store.save(state)
        assertEquals(state, store.load())
    }
}
