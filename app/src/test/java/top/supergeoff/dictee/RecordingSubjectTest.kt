package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class RecordingSubjectTest {
    @Test fun inputContainsOnlyCueTextAndIsBounded() {
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v A>Bonjour\n\n00:00:01.000 --> 00:00:02.000\n${"x".repeat(13000)}\n".toByteArray()
        val text = RecordingSubject.input(vtt)
        assertTrue(text.startsWith("Bonjour"))
        assertFalse(text.contains("00:00"))
        assertEquals(12000, text.length)
    }
    @Test fun cleanValidatesBoundariesAndLines() {
        assertEquals("Sujet", RecordingSubject.clean("  « Sujet. »  "))
        assertEquals("abc", RecordingSubject.clean("abc"))
        assertEquals("x".repeat(80), RecordingSubject.clean("x".repeat(80)))
        assertNull(RecordingSubject.clean("ab"))
        assertNull(RecordingSubject.clean("x".repeat(81)))
        assertNull(RecordingSubject.clean("Un sujet\nDeux lignes"))
    }
}
