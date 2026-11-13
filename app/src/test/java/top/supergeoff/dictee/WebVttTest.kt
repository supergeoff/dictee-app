package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebVttTest {
    private fun parse(text: String) = WebVtt.parse(text.toByteArray(Charsets.UTF_8))

    @Test fun `fichier valide sans segment`() = assertEquals(emptyList<VttCue>(), parse("WEBVTT\n\n"))

    @Test fun `en-tete seul`() = assertEquals(emptyList<VttCue>(), parse("WEBVTT"))

    @Test fun `segments avec balise de locuteur et horodatages`() {
        val cues = parse("WEBVTT\n\n00:00:01.500 --> 00:00:03.000\n<v A>Bonjour\n\n1\n01:02:03.004 --> 01:02:04.000 align:start\n<v B>Salut\nla suite\n")
        assertEquals(listOf(
            VttCue(1_500, 3_000, "Bonjour", "A"),
            VttCue(3_723_004, 3_724_000, "Salut\nla suite", "B"),
        ), cues)
    }

    @Test fun `horodatage court sans heures et CRLF`() =
        assertEquals(VttCue(61_000, 62_000, "Texte", null), parse("WEBVTT\r\n\r\n01:01.000 --> 01:02.000\r\nTexte\r\n").single())

    @Test fun `entites echappees et balises decodees`() =
        assertEquals("a & b < c > d", parse("WEBVTT\n\n00:00.000 --> 00:01.000\n<i>a &amp; b &lt; c</i> &gt; d\n").single().text)

    @Test fun `NOTE et STYLE sont ignores`() =
        assertEquals(1, parse("WEBVTT\n\nNOTE un commentaire\n--> piege\n\nSTYLE\n::cue {}\n\n00:00.000 --> 00:01.000\nX\n").size)

    @Test fun `refus d'un fichier invalide`() {
        assertThrows(VttFormatException::class.java) { parse("") }
        assertThrows(VttFormatException::class.java) { parse("pas du vtt") }
        assertThrows(VttFormatException::class.java) { parse("WEBVTTX\n") }
        assertThrows(VttFormatException::class.java) { parse("WEBVTT\n\n00:00.000 --> demain\ntexte\n") }
        assertThrows(VttFormatException::class.java) { parse("WEBVTT\n\n00:02.000 --> 00:01.000\ntexte\n") }
        assertThrows(VttFormatException::class.java) { WebVtt.parse("WEBVTT\n\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)) }
    }
}
