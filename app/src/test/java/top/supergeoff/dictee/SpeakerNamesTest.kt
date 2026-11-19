package top.supergeoff.dictee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeakerNamesTest {
    private fun vtt(text: String) = text.toByteArray(Charsets.UTF_8)

    private val sample = "WEBVTT\n\n" +
        "NOTE réunion A\n\n" +
        "00:00:00.000 --> 00:00:02.000\n<v A>Bonjour</v>\n\n" +
        "00:00:02.000 --> 00:00:04.000\n<v.loud B>Salut</v>\n\n" +
        "00:00:04.000 --> 00:00:06.000\n<v A>Encore</v>\n\n" +
        "00:00:06.000 --> 00:00:08.000\nSans voix\n\n" +
        "00:00:08.000 --> 00:00:10.000\n<v Alice>Autre</v>\n"

    @Test
    fun `labels rend les etiquettes distinctes dans l'ordre d'apparition`() {
        assertEquals(listOf("A", "B", "Alice"), SpeakerNames.labels(vtt(sample)))
    }

    @Test
    fun `labels est vide sans balise de voix`() {
        assertEquals(emptyList<String>(), SpeakerNames.labels(vtt("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nTexte\n")))
    }

    @Test
    fun `renameInVtt ne change que la valeur des voix visees`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), "A", "Marie")
        val expected = sample.replace("<v A>", "<v Marie>")
        assertArrayEquals(vtt(expected), renamed)
        assertEquals(listOf("Marie", "B", "Alice"), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt garde la classe de voix`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), "B", "Marie")
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.loud Marie>Salut</v>"))
    }

    @Test
    fun `renameInVtt garde CRLF et BOM`() {
        val source = "\uFEFF" + sample.replace("\n", "\r\n")
        val renamed = SpeakerNames.renameInVtt(vtt(source), "A", "Marie")
        assertArrayEquals(vtt(source.replace("<v A>", "<v Marie>")), renamed)
    }

    @Test
    fun `renameInVtt fusionne deux etiquettes`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), "B", "A")
        assertEquals(listOf("A", "Alice"), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt garde le nom tel quel avec un dollar`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), "A", "Jo\$1\\")
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v Jo\$1\\>Bonjour</v>"))
    }

    @Test
    fun `renameInVtt refuse un VTT invalide`() {
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(byteArrayOf(0xC3.toByte(), 0x28), "A", "B")
        }
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(vtt("pas un vtt <v A>"), "A", "B")
        }
    }

    @Test
    fun `replaceInMd remplace en mot entier`() {
        val md = vtt("SPEAKER_00 parle. SPEAKER_000 non. xSPEAKER_00 non. (SPEAKER_00), SPEAKER_00_b non.")
        val result = SpeakerNames.replaceInMd(md, "SPEAKER_00", "Marie")
        assertEquals("Marie parle. SPEAKER_000 non. xSPEAKER_00 non. (Marie), SPEAKER_00_b non.", String(result!!, Charsets.UTF_8))
    }

    @Test
    fun `replaceInMd ne touche pas un prefixe de mot`() {
        val result = SpeakerNames.replaceInMd(vtt("Alice et A"), "A", "Marie")
        assertEquals("Alice et Marie", String(result!!, Charsets.UTF_8))
    }

    @Test
    fun `replaceInMd rend null quand l'etiquette est absente`() {
        assertNull(SpeakerNames.replaceInMd(vtt("Rien ici"), "SPEAKER_00", "Marie"))
    }

    @Test
    fun `replaceInMd garde le dollar du remplacement`() {
        val result = SpeakerNames.replaceInMd(vtt("SPEAKER_00 dit"), "SPEAKER_00", "A\$1\\b")
        assertEquals("A\$1\\b dit", String(result!!, Charsets.UTF_8))
    }

    @Test
    fun `validate normalise et refuse les noms invalides`() {
        assertEquals("Marie", SpeakerNames.validate("  Marie "))
        assertNull(SpeakerNames.validate(""))
        assertNull(SpeakerNames.validate("   "))
        assertNull(SpeakerNames.validate("a".repeat(41)))
        assertNotNull(SpeakerNames.validate("a".repeat(40)))
        assertNull(SpeakerNames.validate("a<b"))
        assertNull(SpeakerNames.validate("a>b"))
        assertNull(SpeakerNames.validate("a&b"))
        assertNull(SpeakerNames.validate("a\nb"))
        assertNull(SpeakerNames.validate("a\rb"))
    }

    @Test
    fun `validate compte les points de code`() {
        val emojis = "😀".repeat(40)
        assertEquals(emojis, SpeakerNames.validate(emojis))
        assertNull(SpeakerNames.validate("😀".repeat(41)))
    }

    @Test
    fun `use rafraichit un nom sans doublon et garde la derniere orthographe`() {
        val book = listOf(SpeakerName("Paul", 20), SpeakerName("Élodie", 10))
        val result = SpeakerNames.use(book, "elodie", 30)
        assertEquals(listOf(SpeakerName("elodie", 30), SpeakerName("Paul", 20)), result)
    }

    @Test
    fun `use ajoute un nouveau nom en tete`() {
        val result = SpeakerNames.use(listOf(SpeakerName("Paul", 20)), "Marie", 30)
        assertEquals(listOf(SpeakerName("Marie", 30), SpeakerName("Paul", 20)), result)
    }

    @Test
    fun `suggestions filtre sans casse ni accents du plus recent au moins recent`() {
        val book = listOf(SpeakerName("Paul", 5), SpeakerName("Élodie", 10), SpeakerName("Melody", 30))
        assertEquals(listOf("Melody", "Élodie"), SpeakerNames.suggestions(book, "elo").map { it.name })
        assertEquals(listOf("Melody", "Élodie", "Paul"), SpeakerNames.suggestions(book, "  ").map { it.name })
    }

    @Test
    fun `rename change un nom du carnet et fusionne un doublon`() {
        val book = listOf(SpeakerName("Paul", 20), SpeakerName("Marie", 10))
        assertEquals(listOf(SpeakerName("Pierre", 20), SpeakerName("Marie", 10)), SpeakerNames.rename(book, "Paul", "Pierre"))
        assertEquals(listOf(SpeakerName("marie", 20)), SpeakerNames.rename(book, "Paul", "marie"))
    }

    @Test
    fun `remove retire un nom sans tenir compte de la casse`() {
        val book = listOf(SpeakerName("Paul", 20), SpeakerName("Marie", 10))
        assertEquals(listOf(SpeakerName("Marie", 10)), SpeakerNames.remove(book, "paul"))
    }
}
