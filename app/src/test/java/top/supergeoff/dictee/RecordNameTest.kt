package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RecordNameTest {
    @Test
    fun `les noms se lisent et s'ecrivent dans les deux sens`() {
        for (type in RecordType.entries) for (kind in FileKind.entries) {
            val f = RecordName.fileName(type, "20261030160502", kind)
            assertEquals(RecordFile(type, "20261030160502", kind), RecordName.parse(f))
        }
        assertEquals("Call-20261030160502.mp3", RecordName.fileName(RecordType.CALL, "20261030160502", FileKind.MP3))
    }

    @Test
    fun `les autres noms sont ignores`() {
        for (f in listOf("call-20261030160502.mp3", "Call-2026103016050.mp3", "Call-20261030160502.mp3.part",
            "Call-20261030160502.mp3.old", "20261030160502.mp3", "Note-20261030160502.wav", "Note-20261030160502 (1).mp3")) assertNull(f, RecordName.parse(f))
        assertEquals("20261030084752", RecordName.legacyMp3("20261030084752.mp3"))
        assertNull(RecordName.legacyMp3("Note-20261030084752.mp3"))
    }

    @Test
    fun `un nom dont la date n'existe pas est ignore`() {
        for (f in listOf("Call-20261340160502.mp3", "Note-20261030250000.vtt", "Call-20260230101010.md")) assertNull(f, RecordName.parse(f))
        for (f in listOf("20261332000000.mp3", "20260431000000.mp3")) assertNull(f, RecordName.legacyMp3(f))
        assertNull(RecordName.dateTimeOrNull("20261340160502"))
        assertEquals(LocalDateTime.of(2026, 10, 30, 16, 5, 2), RecordName.dateTimeOrNull("20261030160502"))
    }

    @Test
    fun `l'indicateur donne le type`() {
        assertEquals(RecordType.NOTE, RecordType.fromFlag(1)); assertEquals(RecordType.CALL, RecordType.fromFlag(0))
        assertNull(RecordType.fromFlag(2)); assertNull(RecordType.fromFlag(-1))
    }

    @Test
    fun `la duree se calcule a partir de la taille`() {
        assertEquals(385L, RecordName.durationSeconds(1_543_572))
        assertEquals("6 min", RecordName.formatDuration(385)); assertEquals("41 min", RecordName.formatDuration(2470))
        assertEquals("59 s", RecordName.formatDuration(59)); assertEquals("1 min", RecordName.formatDuration(89))
        assertEquals("2 min", RecordName.formatDuration(90))
    }

    @Test
    fun `tailles, dates et titres s'affichent comme dans la maquette`() {
        assertEquals("240 Ko", RecordName.formatSize(245_760)); assertEquals("5,5 Mo", RecordName.formatSize(5_767_168))
        assertEquals("1,5 Mo", RecordName.formatSize(1_543_572))
        assertEquals(LocalDateTime.of(2026, 10, 30, 16, 5, 2), RecordName.dateTime("20261030160502"))
        assertEquals("Call · 16:05", RecordName.rowTitle(RecordType.CALL, "20261030160502"))
        assertEquals("Call · 30 oct. 16:05", RecordName.detailTitle(RecordType.CALL, "20261030160502"))
    }
}
