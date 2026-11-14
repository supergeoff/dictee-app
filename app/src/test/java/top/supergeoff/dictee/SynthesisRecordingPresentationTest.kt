package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class SynthesisRecordingPresentationTest {
    private fun recording(name: String, vararg kinds: FileKind) = Recording(
        name, RecordType.NOTE, 100, false,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) }, DisplayState.Recovered,
    )

    @Test fun selectedExclusionsExplainMissingFailedAndActive() {
        val missing = recording("20261101000000", FileKind.VTT)
        val failed = recording("20261102000000", FileKind.MP3)
        val active = recording("20261103000000", FileKind.MP3)
        val complete = recording("20261104000000", FileKind.MP3, FileKind.VTT, FileKind.MD)
        val message = synthesisSelectionMessage(listOf(missing, failed, active, complete),
            setOf(missing.name, failed.name, active.name, complete.name),
            mapOf(failed.name to mapOf(Step.RECOVER to "erreur")), setOf(active.name))!!
        assertTrue(message.contains(missing.name))
        assertTrue(message.contains(failed.name))
        assertTrue(message.contains(active.name))
        assertTrue(message.contains(complete.name))
        assertTrue(message.contains("cours"))
    }

    @Test fun selectionWithoutWorkExplainsCompleteAndActiveRows() {
        val complete = recording("20261101000000", FileKind.MP3, FileKind.VTT, FileKind.MD)
        val running = recording("20261102000000", FileKind.MP3, FileKind.VTT, FileKind.MD)
        val message = synthesisSelectionMessage(listOf(complete, running), setOf(complete.name, running.name), emptyMap(), setOf(running.name))!!
        assertTrue(message.contains("déjà présents"))
        assertTrue(message.contains(complete.name))
        assertTrue(message.contains("cours"))
        assertTrue(message.contains(running.name))
    }

    @Test fun selectionCountUsesMissingMatrixAndNeverReplaces() {
        val rows = listOf(
            recording("20261101000000", FileKind.MP3),
            recording("20261102000000", FileKind.MP3, FileKind.VTT),
            recording("20261103000000", FileKind.MP3, FileKind.MD),
            recording("20261104000000", FileKind.MP3, FileKind.VTT, FileKind.MD),
        )
        assertEquals(3, Recordings.toTranscribe(rows, null, emptyMap()).candidates.size)
        assertEquals(3, Recordings.toTranscribe(rows, rows.map { it.name }.toSet(), emptyMap()).candidates.size)
        assertEquals(0, Recordings.toTranscribe(rows, setOf(rows.last().name), emptyMap()).candidates.size)
    }
}
