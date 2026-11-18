package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class BoxSettingsPresentationTest {
    @Test fun editableOnlyOnlineFreshIdleAndRead() {
        assertTrue(boxSettingsEditable(LinkStatus.CONNECTED, fresh = true, busy = false, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.OFFLINE, fresh = true, busy = false, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.CONNECTING, fresh = true, busy = false, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.NONE, fresh = true, busy = false, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.CONNECTED, fresh = false, busy = false, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.CONNECTED, fresh = true, busy = true, hasStatus = true))
        assertFalse(boxSettingsEditable(LinkStatus.CONNECTED, fresh = true, busy = false, hasStatus = false))
    }

    @Test fun offlineKeepsValuesButForbidsEditing() {
        val status = BoxStatus(batteryPercent = 71, totalMb = 59_934, freeMb = 59_911, noise = 0x09, segmentDuration = 0x02, backlight = 0x00)
        assertEquals("71 %", batteryLabel(status.batteryPercent))
        assertEquals("23 Mo utilisés sur 59 934 Mo", status.storageLabel())
        assertEquals(NoiseReduction.MOYEN, noiseReductionChoice(status.noise))
        assertEquals(SegmentDuration.HEURES_2, segmentDurationChoice(status.segmentDuration))
        assertEquals(false, backlightChoice(status.backlight))
        assertFalse(boxSettingsEditable(LinkStatus.OFFLINE, fresh = false, busy = false, hasStatus = true))
    }

    @Test fun statusTextMarksMissingAndStaleReadings() {
        assertEquals("Non lu", boxSettingsStatusText(hasStatus = false, fresh = false))
        assertEquals("Non lu", boxSettingsStatusText(hasStatus = false, fresh = true))
        assertEquals("Dernières valeurs lues", boxSettingsStatusText(hasStatus = true, fresh = false))
        assertNull(boxSettingsStatusText(hasStatus = true, fresh = true))
    }

    @Test fun unknownRawValuesHaveNoActiveChoice() {
        assertNull(noiseReductionChoice(0x07))
        assertNull(segmentDurationChoice(0x09))
        assertNull(backlightChoice(0x02))
    }

    @Test fun noiseChoicesCoverTheFourDocumentedLevels() {
        assertEquals(
            listOf(0x00 to "Coupée", 0x05 to "Faible", 0x09 to "Moyenne", 0x0f to "Élevé"),
            BOX_NOISE_CHOICES.map { it.first.raw to it.second },
        )
        BOX_NOISE_CHOICES.forEach { (choice, _) -> assertEquals(choice, noiseReductionChoice(choice.raw)) }
    }

    @Test fun durationChoicesCoverTheFourDocumentedDurations() {
        assertEquals(
            listOf("30 minutes", "1 heure", "2 heures", "3 heures"),
            BOX_DURATION_CHOICES.map { it.second },
        )
        BOX_DURATION_CHOICES.forEach { (choice, _) -> assertEquals(choice, segmentDurationChoice(choice.raw)) }
    }

    @Test fun backlightChoicesOfferOffAndOn() {
        assertEquals(listOf(false to "Désactivé", true to "Activé"), BOX_BACKLIGHT_CHOICES)
        assertEquals(false, backlightChoice(0x00))
        assertEquals(true, backlightChoice(0x01))
    }
}
