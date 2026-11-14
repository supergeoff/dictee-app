package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class SynthesisSelectionTest {
    private fun recording(name: String, vararg kinds: FileKind): Recording = Recording(
        name, RecordType.NOTE, 100, false,
        kinds.associateWith { PhoneFile(RecordName.fileName(RecordType.NOTE, name, it), 100) },
        DisplayState.Recovered,
    )

    @Test fun missingMatrixIsIdenticalWithOrWithoutSelection() {
        val a = recording("20261101000000", FileKind.MP3)
        val b = recording("20261102000000", FileKind.MP3, FileKind.VTT)
        val c = recording("20261103000000", FileKind.MP3, FileKind.MD)
        val d = recording("20261104000000", FileKind.MP3, FileKind.VTT, FileKind.MD)
        val recordings = listOf(d, c, b, a)
        val expected = listOf(
            FilePlan(a, true, true, false, false),
            FilePlan(b, false, true, false, false),
            FilePlan(c, true, false, false, false),
        ) to emptyMap<String, TranscriptionExclusion>()
        assertEquals(expected, SynthesisSelection.plan(recordings, null, emptyMap(), emptySet(), RequestedWork.MISSING))
        assertEquals(expected, SynthesisSelection.plan(recordings, recordings.map { it.name }.toSet(), emptyMap(), emptySet(), RequestedWork.MISSING))
    }

    @Test fun redoIsExplicitAndExcludesActiveOrUnusable() {
        val a = recording("20261101000000", FileKind.MP3, FileKind.VTT, FileKind.MD)
        val b = recording("20261102000000", FileKind.MP3)
        val missing = recording("20261103000000", FileKind.VTT)
        val failed = recording("20261104000000", FileKind.MP3, FileKind.VTT)
        val active = recording("20261105000000", FileKind.MP3, FileKind.VTT)
        val recordings = listOf(active, failed, missing, b, a)
        val errors = mapOf(failed.name to mapOf(Step.RECOVER to "coupure"))
        fun plan(name: String, request: RequestedWork) =
            SynthesisSelection.plan(recordings, setOf(name), errors, setOf(active.name), request)
        assertEquals(listOf(FilePlan(a, true, true, true, true)), plan(a.name, RequestedWork.REDO_VTT_AND_MD).first)
        assertEquals(listOf(FilePlan(b, true, true, true, true)), plan(b.name, RequestedWork.REDO_VTT_AND_MD).first)
        assertEquals(listOf(FilePlan(a, false, true, false, true)), plan(a.name, RequestedWork.REDO_MD).first)
        assertEquals(TranscriptionExclusion.MISSING_VTT, plan(b.name, RequestedWork.REDO_MD).second[b.name])
        assertEquals(TranscriptionExclusion.MISSING_MP3, plan(missing.name, RequestedWork.REDO_VTT_AND_MD).second[missing.name])
        assertEquals(TranscriptionExclusion.RECOVERY_FAILED, plan(failed.name, RequestedWork.REDO_MD).second[failed.name])
        assertEquals(TranscriptionExclusion.ALREADY_ACTIVE, plan(active.name, RequestedWork.REDO_MD).second[active.name])
        assertEquals(TranscriptionExclusion.MISSING_MP3, plan("inconnu", RequestedWork.REDO_MD).second["inconnu"])
        assertEquals(TranscriptionExclusion.MISSING_MP3,
            SynthesisSelection.plan(recordings, setOf("inconnu"), errors, emptySet(), RequestedWork.MISSING).second["inconnu"])
        for (request in listOf(RequestedWork.REDO_MD, RequestedWork.REDO_VTT_AND_MD)) for (selection in listOf(null, emptySet(), setOf(a.name, b.name))) {
            try {
                SynthesisSelection.plan(recordings, selection, errors, emptySet(), request)
                fail("redo requires exactly one selected name")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun missingPlansAreOrderedOldestFirstAndExcludeUnusable() {
        val a = recording("20261101000000", FileKind.MP3)
        val b = recording("20261102000000", FileKind.MP3)
        val result = SynthesisSelection.plan(listOf(b, a), null, emptyMap(), emptySet(), RequestedWork.MISSING)
        assertEquals(listOf(a.name, b.name), result.first.map { it.recording.name })
    }
}
