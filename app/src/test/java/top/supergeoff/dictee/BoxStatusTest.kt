package top.supergeoff.dictee

import org.junit.Assert.*
import org.junit.Test

class BoxStatusTest {

    private fun statusPayload(
        total: Int = 59_934,
        free: Int = 59_911,
        battery: Int = 71,
        noise: Int = 0x09,
        duration: Int = 0x02,
        backlight: Int = 0x01,
    ): ByteArray {
        val payload = ByteArray(52)
        payload[0] = (total shr 24).toByte()
        payload[1] = (total shr 16).toByte()
        payload[2] = (total shr 8).toByte()
        payload[3] = total.toByte()
        payload[4] = (free shr 24).toByte()
        payload[5] = (free shr 16).toByte()
        payload[6] = (free shr 8).toByte()
        payload[7] = free.toByte()
        payload[9] = battery.toByte()
        payload[39] = noise.toByte()
        payload[41] = duration.toByte()
        payload[42] = backlight.toByte()
        return payload
    }

    @Test fun parseLitTousLesChamps() {
        val status = BoxStatus.parse(statusPayload())
        assertEquals(BoxStatus(71, 59_934L, 59_911L, 0x09, 0x02, 0x01), status)
        assertEquals(71, status.batteryPercent)
        assertEquals(59_934L, status.totalMb)
        assertEquals(59_911L, status.freeMb)
        assertEquals(0x09, status.noise)
        assertEquals(0x02, status.segmentDuration)
        assertEquals(0x01, status.backlight)
    }

    @Test fun lesEntiersDontLeBitDePoidsFortVautUnSontNonSignes() {
        val status = BoxStatus.parse(statusPayload(total = 0xEA1E, free = 0xEA07, battery = 100,
            noise = 0xFF, duration = 0xFF, backlight = 0xFF))
        assertEquals(59_934L, status.totalMb)
        assertEquals(59_911L, status.freeMb)
        assertEquals(100, status.batteryPercent)
        assertEquals(0xFF, status.noise)
        assertEquals(0xFF, status.segmentDuration)
        assertEquals(0xFF, status.backlight)
        assertEquals(4_294_967_295L, BoxStatus.parse(statusPayload(total = -1)).totalMb)
    }

    @Test fun uneBatterieHorsPlageEstRejetee() {
        for (battery in listOf(101, 255)) {
            assertThrows(IllegalArgumentException::class.java) { BoxStatus.parse(statusPayload(battery = battery)) }
        }
    }

    @Test fun lesQuatreNiveauxDeBruitOntUnLibelle() {
        val labels = NoiseReduction.entries.associate { it.raw to it.label }
        assertEquals("Désactivé", boxSettingLabel(0x00, labels))
        assertEquals("Faible", boxSettingLabel(0x05, labels))
        assertEquals("Moyen", boxSettingLabel(0x09, labels))
        assertEquals("Élevé", boxSettingLabel(0x0f, labels))
        assertEquals(0x0f, NoiseReduction.entries.first { it.label == "Élevé" }.raw)
    }

    @Test fun lesQuatreDureesDeSegmentOntDesMinutes() {
        val parRaw = SegmentDuration.entries.associateBy { it.raw }
        assertEquals(30, parRaw.getValue(0x00).minutes)
        assertEquals("30 minutes", parRaw.getValue(0x00).label)
        assertEquals(60, parRaw.getValue(0x01).minutes)
        assertEquals("1 heure", parRaw.getValue(0x01).label)
        assertEquals(120, parRaw.getValue(0x02).minutes)
        assertEquals("2 heures", parRaw.getValue(0x02).label)
        assertEquals(180, parRaw.getValue(0x03).minutes)
        assertEquals("3 heures", parRaw.getValue(0x03).label)
    }

    @Test fun lesDeuxRetroEclairagesOntUnLibelle() {
        val labels = mapOf(0x00 to "Désactivé", 0x01 to "Activé")
        assertEquals("Désactivé", boxSettingLabel(0x00, labels))
        assertEquals("Activé", boxSettingLabel(0x01, labels))
        assertEquals(0x01, BoxStatus.parse(statusPayload()).backlight)
        assertEquals(0x00, BoxStatus.parse(statusPayload(backlight = 0x00)).backlight)
    }

    @Test fun uneValeurDeReglageInconnueEstConserveeEtPresenteeCommeInconnue() {
        val status = BoxStatus.parse(statusPayload(noise = 0x7f, duration = 0x04))
        assertEquals(0x7f, status.noise)
        assertEquals(0x04, status.segmentDuration)
        assertEquals("Inconnu", boxSettingLabel(status.noise, NoiseReduction.entries.associate { it.raw to it.label }))
        assertEquals("Inconnu", boxSettingLabel(status.segmentDuration,
            SegmentDuration.entries.associate { it.raw to it.label }))
        assertEquals("Inconnu", boxSettingLabel(0x7f, mapOf(0x00 to "Désactivé", 0x01 to "Activé")))
    }

    @Test fun lesLongueursCinquanteEtUnEtCinquanteEtTroisSontRejetees() {
        assertThrows(IllegalArgumentException::class.java) {
            BoxStatus.parse(ByteArray(51))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoxStatus.parse(ByteArray(53))
        }
    }

    @Test fun leStockageEstPresenteEnMoAvecEspaces() {
        assertEquals("23 Mo utilisés sur 59 934 Mo", BoxStatus.parse(statusPayload()).storageLabel())
    }

    @Test fun `un stockage incoherent est inconnu`() {
        val status = BoxStatus(71, 59_934L, 59_935L, 0x09, 0x02, 0x01)
        assertEquals("Stockage inconnu", status.storageLabel())
    }
}
