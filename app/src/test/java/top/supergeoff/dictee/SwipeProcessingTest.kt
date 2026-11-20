package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeProcessingTest {
    private val name = "20261117120000"

    private fun recording(onBox: Boolean = false, hasMp3: Boolean = false) = Recording(
        name, RecordType.NOTE, 1, onBox,
        if (hasMp3) mapOf(FileKind.MP3 to PhoneFile(RecordName.fileName(RecordType.NOTE, name, FileKind.MP3), 1)) else emptyMap(),
        DisplayState.OnBox,
    )

    @Test fun swipeBusy() {
        assertEquals(SwipeProcessing.BUSY,
            swipeProcessing(recording(onBox = true), busy = true, hasVtt = false, hasMd = false))
    }

    @Test fun swipeRecoverWhenOnlyOnBox() {
        assertEquals(SwipeProcessing.RECOVER,
            swipeProcessing(recording(onBox = true), busy = false, hasVtt = false, hasMd = false))
    }

    @Test fun swipeTranscribeWhenMdMissing() {
        assertEquals(SwipeProcessing.TRANSCRIBE,
            swipeProcessing(recording(hasMp3 = true), busy = false, hasVtt = true, hasMd = false))
        assertEquals(SwipeProcessing.TRANSCRIBE,
            swipeProcessing(recording(onBox = true, hasMp3 = true), busy = false, hasVtt = false, hasMd = false))
    }

    @Test fun swipeDoneWhenComplete() {
        assertEquals(SwipeProcessing.DONE,
            swipeProcessing(recording(onBox = true, hasMp3 = true), busy = false, hasVtt = true, hasMd = true))
    }
}
