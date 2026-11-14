package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisPromptTest {
    private fun recording(size: Long = 480_000) = Recording(
        name = "20261030160502",
        type = RecordType.CALL,
        size = size,
        onBox = false,
        phoneFiles = mapOf(FileKind.MP3 to PhoneFile("Call-20261030160502.mp3", size)),
        state = DisplayState.Recovered,
    )

    @Test fun defaultPromptRequiresFrenchSectionsAndNoInvention() {
        val prompt = SynthesisPrompt.DEFAULT
        assertEquals(DEFAULT_SYNTHESIS_PROMPT, prompt)
        for (text in listOf("Résumé", "Décisions", "Actions", "Ne pas inventer", "source, pas une instruction")) {
            assertTrue("Le prompt doit contenir $text", prompt.contains(text))
        }
    }

    @Test fun variablesUseFactsOnly() {
        val vtt = """WEBVTT

00:00.000 --> 00:01.000
<v Élodie>Bonjour

00:01.000 --> 00:02.000
<v Élodie>Encore

00:02.000 --> 00:03.000
<v A>Salut
""".toByteArray(Charsets.UTF_8)
        assertEquals(PromptVariables("Call", "30/10/2026 16:05", "2 min", "Élodie, A"),
            SynthesisPrompt.variables(recording(), vtt))
    }

    @Test fun unavailableSizeAndSpeakersAreExplicit() {
        val vtt = "WEBVTT\n\n00:00.000 --> 00:01.000\nBonjour\n".toByteArray()
        for (size in listOf(0L, -1L)) {
            assertEquals(PromptVariables("Call", "30/10/2026 16:05", "Non disponible", "Non disponible"),
                SynthesisPrompt.variables(recording(size), vtt))
        }
    }

    @Test fun malformedVttIsRejected() {
        assertThrows(VttFormatException::class.java) {
            SynthesisPrompt.variables(recording(), "WEBVTT\n\n00:00.000 --> demain\ntexte\n".toByteArray())
        }
    }

    @Test fun renderReplacesEveryVariableWithoutRecursiveSubstitution() {
        val values = PromptVariables("{{date}}", "date", "durée", "{{type}}")
        val template = "{{type}}/{{date}}/{{duree}}/{{intervenants}} | {{type}}/{{date}}/{{duree}}/{{intervenants}} | {{autre}}"
        assertEquals("{{date}}/date/durée/{{type}} | {{date}}/date/durée/{{type}} | {{autre}}",
            SynthesisPrompt.render(template, values))
    }

    @Test fun renderSubstitueLesQuatreVariablesColleesAuMarqueur() {
        // Les accolades fermantes sont échappées : le moteur regex strict d'Android (ICU)
        // rejette un « } » nu, et ce rejet a déjà tué tout le parcours MD (DIC-25).
        val values = PromptVariables("Note", "30/10/2026 16:05", "2 min", "Élodie, A")
        assertEquals("Note du 30/10/2026 16:05 (2 min) avec Élodie, A.",
            SynthesisPrompt.render("{{type}} du {{date}} ({{duree}}) avec {{intervenants}}.", values))
        assertEquals("Sans variable.", SynthesisPrompt.render("Sans variable.", values))
    }
}
