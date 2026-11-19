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

    private fun label(current: String, original: String? = current) = SpeakerLabel(current, original)
    private val a = label("A")
    private val b = label("B")
    private val alice = label("Alice")

    @Test
    fun `labels rend les locuteurs distincts dans l'ordre d'apparition`() {
        assertEquals(listOf(a, b, alice), SpeakerNames.labels(vtt(sample)))
    }

    @Test
    fun `firstCueStarts rend le debut du premier segment de chaque locuteur`() {
        assertEquals(mapOf(a to 0L, b to 2000L, alice to 8000L), SpeakerNames.firstCueStarts(vtt(sample)))
    }

    @Test
    fun `firstCueStarts distingue deux locuteurs qui portent le meme nom`() {
        val source = "WEBVTT\n\n" +
            "00:00:01.500 --> 00:00:02.000\n<v.spk-A Marie>Un</v>\n\n" +
            "00:00:03.000 --> 00:00:04.000\n<v.spk-B Marie>Deux</v>\n\n" +
            "00:01:05.250 --> 00:01:06.000\n<v.spk-B Marie>Trois</v>\n\n" +
            "01:00:00.000 --> 01:00:01.000\n<v.spk-A Marie>Quatre</v>\n"
        assertEquals(
            mapOf(label("Marie", "A") to 1500L, label("Marie", "B") to 3000L),
            SpeakerNames.firstCueStarts(vtt(source)),
        )
    }

    @Test
    fun `firstCueStarts a les memes cles que labels`() {
        val bytes = vtt(sample)
        assertEquals(SpeakerNames.labels(bytes), SpeakerNames.firstCueStarts(bytes).keys.toList())
    }

    @Test
    fun `labels est vide sans balise de voix`() {
        assertEquals(emptyList<SpeakerLabel>(), SpeakerNames.labels(vtt("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nTexte\n")))
    }

    @Test
    fun `labels expose le nom courant et l'origine de la classe spk`() {
        val source = "WEBVTT\n\n" +
            "00:00:00.000 --> 00:00:01.000\n<v.spk-A Marie>Un</v>\n\n" +
            "00:00:01.000 --> 00:00:02.000\n<v.loud.spk-B_2 Paul D.>Deux</v>\n\n" +
            "00:00:02.000 --> 00:00:03.000\n<v Élodie>Trois</v>\n\n" +
            "00:00:03.000 --> 00:00:04.000\n<v.spk-A Marie>Quatre</v>\n\n" +
            "00:00:04.000 --> 00:00:05.000\n<v Paul D.>Cinq</v>\n"
        assertEquals(
            listOf(label("Marie", "A"), label("Paul D.", "B_2"), label("Élodie", null), label("Paul D.", null)),
            SpeakerNames.labels(vtt(source)),
        )
    }

    @Test
    fun `WebVtt rend le nom courant d'une voix qui porte une classe spk`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Paul D."))
        assertEquals(listOf("Marie", "Paul D.", "Marie", null, "Alice"), WebVtt.parse(renamed).map { it.speaker })
    }

    @Test
    fun `renameInVtt garde l'origine dans une classe spk et ne change que les voix visees`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie"))
        val expected = sample.replace("<v A>", "<v.spk-A Marie>")
        assertArrayEquals(vtt(expected), renamed)
        assertEquals(listOf(label("Marie", "A"), b, alice), SpeakerNames.labels(renamed))
    }

    private val withNonCueBlocks = "WEBVTT\n\n" +
        "NOTE\n<v A>dans une note\n\n" +
        "STYLE\n::cue(v[voice=\"A\"]) { color: red }\n<v A>\n\n" +
        "REGION\n<v A>\n\n" +
        "00:00:00.000 --> 00:00:02.000\n<v A>Bonjour <v A>milieu</v></v>\n<v A>deuxième ligne</v>\n"

    @Test
    fun `labels ignore les balises hors du debut de payload d'un segment`() {
        val source = "WEBVTT\n\nNOTE\n<v Z>note\n\n00:00:00.000 --> 00:00:01.000\nTexte <v Y>milieu</v>\n\n00:00:01.000 --> 00:00:02.000\n<v B>Oui</v>\n"
        assertEquals(listOf(b), SpeakerNames.labels(vtt(source)))
    }

    @Test
    fun `renameInVtt ne touche ni NOTE ni STYLE ni REGION ni le milieu d'un segment`() {
        val renamed = SpeakerNames.renameInVtt(vtt(withNonCueBlocks), mapOf(a to "Marie"))
        val expected = withNonCueBlocks.replace(
            "00:00:00.000 --> 00:00:02.000\n<v A>Bonjour", "00:00:00.000 --> 00:00:02.000\n<v.spk-A Marie>Bonjour")
        assertArrayEquals(vtt(expected), renamed)
    }

    @Test
    fun `renameInVtt ajoute la classe spk apres les classes existantes`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(b to "Marie"))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.loud.spk-B Marie>Salut</v>"))
    }

    @Test
    fun `renameInVtt garde la classe spk existante au renommage suivant`() {
        val first = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie"))
        val second = SpeakerNames.renameInVtt(first, mapOf(label("Marie", "A") to "Paul"))
        assertArrayEquals(vtt(sample.replace("<v A>", "<v.spk-A Paul>")), second)
        val third = SpeakerNames.renameInVtt(second, mapOf(label("Paul", "A") to "Jeanne"))
        assertEquals(listOf(label("Jeanne", "A"), b, alice), SpeakerNames.labels(third))
    }

    @Test
    fun `renameInVtt garde l'ordre des classes existantes avec une classe spk`() {
        val source = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v.spk-A.loud Marie>Un</v>\n"
        val renamed = SpeakerNames.renameInVtt(vtt(source), mapOf(label("Marie", "A") to "Paul"))
        assertArrayEquals(vtt(source.replace("Marie", "Paul")), renamed)
    }

    @Test
    fun `renameInVtt retablit l'origine en retirant seulement la classe spk`() {
        val named = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Paul D."))
        val restored = SpeakerNames.renameInVtt(named, mapOf(label("Marie", "A") to "A", label("Paul D.", "B") to "B"))
        assertArrayEquals(vtt(sample), restored)
    }

    @Test
    fun `renameInVtt retablit une origine sans autre classe en balise nue`() {
        val source = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v.spk-A Marie>Un</v>\n"
        val restored = SpeakerNames.renameInVtt(vtt(source), mapOf(label("Marie", "A") to "A"))
        assertArrayEquals(vtt("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v A>Un</v>\n"), restored)
    }

    @Test
    fun `renameInVtt sans origine valide ne pose pas de classe spk`() {
        val source = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<v Élodie>Un</v>\n\n00:00:01.000 --> 00:00:02.000\n<v Paul D.>Deux</v>\n"
        val renamed = SpeakerNames.renameInVtt(vtt(source), mapOf(label("Élodie", null) to "Marie", label("Paul D.", null) to "Paul"))
        assertArrayEquals(vtt(source.replace("Élodie", "Marie").replace("Paul D.", "Paul")), renamed)
        assertEquals(listOf(label("Marie"), label("Paul")), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt garde CRLF et BOM`() {
        val source = "\uFEFF" + sample.replace("\n", "\r\n")
        val renamed = SpeakerNames.renameInVtt(vtt(source), mapOf(a to "Marie"))
        assertArrayEquals(vtt(source.replace("<v A>", "<v.spk-A Marie>")), renamed)
    }

    @Test
    fun `renameInVtt fusionne deux locuteurs en gardant leur origine`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Marie"))
        assertEquals(listOf(label("Marie", "A"), label("Marie", "B"), alice), SpeakerNames.labels(renamed))
    }

    @Test
    fun `une fusion puis un retour rend les deux etiquettes d'origine`() {
        val merged = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Marie"))
        val restored = SpeakerNames.renameInVtt(merged, mapOf(label("Marie", "A") to "A", label("Marie", "B") to "B"))
        assertArrayEquals(vtt(sample), restored)
    }

    @Test
    fun `un retour partiel d'une fusion laisse l'autre locuteur sous le nom`() {
        val merged = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Marie"))
        val partial = SpeakerNames.renameInVtt(merged, mapOf(label("Marie", "A") to "A"))
        assertEquals(listOf(a, label("Marie", "B"), alice), SpeakerNames.labels(partial))
    }

    @Test
    fun `renameInVtt envoye vers l'etiquette d'un autre locuteur fusionne par le nom`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(b to "A"))
        assertEquals(listOf(a, label("A", "B"), alice), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt garde le nom tel quel avec un dollar`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Jo\$1\\"))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.spk-A Jo\$1\\>Bonjour</v>"))
    }

    @Test
    fun `renameInVtt applique plusieurs correspondances en une passe et laisse les autres locuteurs`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "Marie", b to "Paul D."))
        val expected = sample.replace("<v A>", "<v.spk-A Marie>").replace("<v.loud B>", "<v.loud.spk-B Paul D.>")
        assertArrayEquals(vtt(expected), renamed)
        assertEquals(listOf(label("Marie", "A"), label("Paul D.", "B"), alice), SpeakerNames.labels(renamed))
    }

    @Test
    fun `renameInVtt echange deux noms sans enchainer les remplacements`() {
        val renamed = SpeakerNames.renameInVtt(vtt(sample), mapOf(a to "B", b to "A"))
        assertEquals(listOf(label("B", "A"), label("A", "B"), alice), SpeakerNames.labels(renamed))
        assertEquals(true, String(renamed, Charsets.UTF_8).contains("<v.loud.spk-B A>Salut</v>"))
    }

    @Test
    fun `renameInVtt sans correspondance rend le fichier identique`() {
        assertArrayEquals(vtt(sample), SpeakerNames.renameInVtt(vtt(sample), emptyMap()))
    }

    @Test
    fun `renameInVtt refuse un VTT invalide`() {
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(byteArrayOf(0xC3.toByte(), 0x28), mapOf(a to "B"))
        }
        assertThrows(VttFormatException::class.java) {
            SpeakerNames.renameInVtt(vtt("pas un vtt <v A>"), mapOf(a to "B"))
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
