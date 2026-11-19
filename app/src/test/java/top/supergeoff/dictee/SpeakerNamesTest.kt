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
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("A" to "Marie"))
        val expected = sample.replace("<v A>", "<v Marie>")
        assertArrayEquals(vtt(expected), renamed)
        assertEquals(listOf("Marie", "B", "Alice"), SpeakerNames.labels(renamed))
    }

    private val withNonCueBlocks = "WEBVTT\n\n" +
        "NOTE\n<v A>dans une note\n\n" +
        "STYLE\n::cue(v[voice=\"A\"]) { color: red }\n<v A>\n\n" +
        "REGION\n<v A>\n\n" +
        "00:00:00.000 --> 00:00:02.000\n<v A>Bonjour <v A>milieu</v></v>\n<v A>deuxième ligne</v>\n"

    @Test
    fun `labels ignore les balises hors du debut de payload d'un segment`() {
        val source = "WEBVTT\n\nNOTE\n<v Z>note\n\n00:00:00.000 --> 00:00:01.000\nTexte <v Y>milieu</v>\n\n00:00:01.000 --> 00:00:02.000\n<v B>Oui</v>\n"
        assertEquals(listOf("B"), SpeakerNames.labels(vtt(source)))
    }

    @Test
    fun `renameInVtt ne touche ni NOTE ni STYLE ni REGION ni le milieu d'un segment`() {
        val renamed = SpeakerNames.renameInVtt(vtt(withNonCueBlocks), mapOf("A" to "Marie"))
        val expected = withNonCueBlocks.replace(
            "00:00:00.000 --> 00:00:02.000\n<v A>Bonjour", "00:00:00.000 --> 00:00:02.000\n<v Marie>Bonjour")
        assertArrayEquals(vtt(expected), renamed)
    }

    @Test
    fun `renameInVtt garde la classe de voix`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("B" to "Marie"))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.loud Marie>Salut</v>"))
    }

    @Test
    fun `renameInVtt garde CRLF et BOM`() {
        val source = "\uFEFF" + sample.replace("\n", "\r\n")
        val renamed = SpeakerNames.renameInVtt(vtt(source), mapOf("A" to "Marie"))
        assertArrayEquals(vtt(source.replace("<v A>", "<v Marie>")), renamed)
    }

    @Test
    fun `renameInVtt fusionne deux etiquettes`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("B" to "A"))
        assertEquals(listOf("A", "Alice"), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt garde le nom tel quel avec un dollar`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("A" to "Jo\$1\\"))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v Jo\$1\\>Bonjour</v>"))
    }

    @Test
    fun `renameInVtt applique plusieurs correspondances en une passe et laisse les autres etiquettes`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("A" to "Marie", "B" to "Paul D."))
        val expected = sample.replace("<v A>", "<v Marie>").replace("<v.loud B>", "<v.loud Paul D.>")
        assertArrayEquals(vtt(expected), renamed)
        assertEquals(listOf("Marie", "Paul D.", "Alice"), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt echange deux etiquettes sans enchainer les remplacements`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("A" to "B", "B" to "A"))
        assertEquals(listOf("B", "A", "Alice"), SpeakerNames.labels(renamed))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.loud A>Salut</v>"))
    }

    @Test
    fun `renameInVtt fusionne plusieurs etiquettes vers le meme nom`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf("A" to "Marie", "B" to "Marie"))
        assertEquals(listOf("Marie", "Alice"), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt sans correspondance rend le fichier identique`() {
        assertArrayEquals(vtt(sample), SpeakerNames.renameInVtt(vtt(sample), emptyMap()))
    }

    @Test
    fun `renameInVtt refuse un VTT invalide`() {
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(byteArrayOf(0xC3.toByte(), 0x28), mapOf("A" to "B"))
        }
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(vtt("pas un vtt <v A>"), mapOf("A" to "B"))
        }
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
