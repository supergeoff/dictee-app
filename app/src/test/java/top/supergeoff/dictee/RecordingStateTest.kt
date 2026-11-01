package top.supergeoff.dictee

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecordingStateTest {
    private val n = "20261030160502"
    private val onBox = listOf(ListItem.Entry(n, 9_880_000, 0))
    private fun one(box: List<ListItem.Entry> = onBox, phone: List<String> = emptyList(), drive: List<String>? = null,
                    errors: Map<String, Map<Step, String>> = emptyMap(), busy: Busy = Busy()) =
        Recordings.merge(box, phone.map { PhoneFile(it, 4000) }, drive?.let { d -> DriveListing(d.associateWith { 4000L }) }, errors, busy).single()

    @Test fun `ligne 1, erreur`() =
        assertEquals(DisplayState.Error("coupure"), one(phone = listOf("Call-$n.mp3"), errors = mapOf(n to mapOf(Step.RECOVER to "coupure"))).state)
    @Test fun `ligne 2, recuperation en cours`() =
        assertEquals("Récupération 40 %", one(busy = Busy(recovering = n, recoveringPercent = 40)).state.label)
    @Test fun `ligne 3, envoi en cours`() =
        assertEquals(DisplayState.Sending, one(phone = listOf("Call-$n.mp3"), drive = emptyList(), busy = Busy(sending = n)).state)
    @Test fun `ligne 5, sur Drive avec le nom prefixe ou le nom de la 0_1_5`() {
        assertEquals(DisplayState.OnDrive, one(phone = listOf("Call-$n.mp3"), drive = listOf("Call-$n.mp3")).state)
        assertEquals(DisplayState.OnDrive, one(phone = listOf("Call-$n.mp3"), drive = listOf("$n.mp3")).state)
        assertEquals(DisplayState.Recovered, one(phone = listOf("Call-$n.mp3", "Call-$n.vtt"), drive = listOf("Call-$n.mp3")).state)
    }
    @Test fun `ligne 6, pret`() = assertEquals(DisplayState.Ready, one(phone = listOf("Call-$n.mp3", "Call-$n.vtt", "Call-$n.md")).state)
    @Test fun `ligne 7, recupere`() = assertEquals(DisplayState.Recovered, one(phone = listOf("Call-$n.mp3")).state)
    @Test fun `ligne 8, sur le boitier`() = assertEquals(DisplayState.OnBox, one().state)

    @Test
    fun `plusieurs erreurs donnent le message de la premiere etape`() {
        val errors = mapOf(n to mapOf(Step.SEND to "envoi", Step.RECOVER to "coupure"))
        assertEquals(DisplayState.Error("coupure"), one(phone = listOf("Call-$n.mp3"), errors = errors).state)
    }

    @Test
    fun `les trois sources se reunissent sans doublon, du plus recent au plus ancien`() {
        val list = Recordings.merge(
            listOf(ListItem.Entry("20261031101200", 5_767_168, 0), ListItem.Entry("20261030084701", 20_482, 1)),
            listOf(PhoneFile("Note-20261030084701.mp3", 20_482), PhoneFile("Call-20261029081500.mp3", 2_900_000),
                PhoneFile("Call-20261029081500 (1).mp3", 1)),
            DriveListing(mapOf("20261028190702.mp3" to 174_274L, "Call-20261027080000.vtt" to 10L)), emptyMap(), Busy())
        assertEquals(listOf("20261031101200", "20261030084701", "20261029081500", "20261028190702"), list.map { it.name })
        assertEquals(listOf(RecordType.CALL, RecordType.NOTE, RecordType.CALL, RecordType.NOTE), list.map { it.type })
        assertEquals(listOf(true, true, false, false), list.map { it.onBox })
        assertEquals(174_274L, list[3].size)
    }

    @Test
    fun `un nom invalide est ignore dans les trois sources`() {
        val list = Recordings.merge(
            listOf(ListItem.Entry("20261340160502", 1, 0), ListItem.Entry("n'importe quoi", 1, 0), ListItem.Entry(n, 1, 0)),
            listOf(PhoneFile("Call-20261340160502.mp3", 1)), DriveListing(mapOf("20261332000000.mp3" to 1L)), emptyMap(), Busy())
        assertEquals(listOf(n), list.map { it.name })
    }

    @Test
    fun `un indicateur inconnu donne Note et se signale`() {
        val seen = mutableListOf<Pair<String, Int>>()
        val r = Recordings.merge(listOf(ListItem.Entry(n, 4000, 7)), emptyList(), null, emptyMap(), Busy()) { name, flag -> seen += name to flag }.single()
        assertEquals(RecordType.NOTE, r.type); assertEquals(listOf(n to 7), seen)
    }

    @Test
    fun `a recuperer et a envoyer`() {
        val drive = DriveListing(mapOf("20261028190702.mp3" to 1L, "Note-20261030084701.mp3" to 20_482L))
        val list = Recordings.merge(
            listOf(ListItem.Entry("20261031101200", 1, 0), ListItem.Entry("20261030084701", 20_482, 1), ListItem.Entry("20261028190702", 1, 0)),
            listOf(PhoneFile("Note-20261030084701.mp3", 20_482), PhoneFile("Note-20261030084701.vtt", 300)), drive, emptyMap(), Busy())
        assertEquals(listOf("20261031101200"), Recordings.toRecover(list, drive).map { it.name })
        assertEquals(listOf("20261030084701"), Recordings.toSend(list, drive).map { it.name })
        assertEquals(listOf("Note-20261030084701.vtt"), Recordings.missingInDrive(list[1], drive).map { it.fileName })
        assertEquals(listOf("20261030084701"), Recordings.toSend(list, null).map { it.name })
    }

    @Test
    fun `un enregistrement dont la recuperation est en erreur n'est pas a envoyer`() {
        val other = "20261029081500"
        val list = Recordings.merge(
            listOf(ListItem.Entry(n, 100, 0), ListItem.Entry(other, 100, 0)),
            listOf(PhoneFile("Call-$n.mp3", 40), PhoneFile("Call-$other.mp3", 100)), null, emptyMap(), Busy())
        val unlisted = DriveListing(emptyMap())
        val recoverError = mapOf(n to mapOf(Step.RECOVER to "coupure"))
        val sendError = mapOf(n to mapOf(Step.SEND to "403"))
        val both = mapOf(n to mapOf(Step.RECOVER to "coupure", Step.SEND to "403"))
        // Le MP3 tronqué ne part ni avec la liste Drive, ni sans elle, ni par la sélection explicite.
        assertEquals(listOf(other), Recordings.toSend(list, null, recoverError).map { it.name })
        assertEquals(listOf(other), Recordings.toSend(list, unlisted, recoverError).map { it.name })
        assertEquals(listOf(other), Recordings.sendable(list, recoverError).map { it.name })
        assertEquals(listOf(other), Recordings.toSend(list, unlisted, both).map { it.name })
        // Une erreur d'envoi, ou aucune erreur, laisse la sélection telle qu'elle était.
        assertEquals(listOf(n, other), Recordings.toSend(list, unlisted, sendError).map { it.name })
        assertEquals(listOf(n, other), Recordings.toSend(list, null, sendError).map { it.name })
        assertEquals(listOf(n, other), Recordings.toSend(list, unlisted).map { it.name })
        assertEquals(listOf(n, other), Recordings.sendable(list, emptyMap()).map { it.name })
        // Un enregistrement déjà dans Drive reste exclu, quelle que soit l'erreur.
        val onDrive = DriveListing(mapOf("Call-$other.mp3" to 100L))
        assertEquals(listOf(n), Recordings.toSend(list, onDrive, sendError).map { it.name })
    }

    @Test
    fun `les groupes par jour et l'avancement s'ecrivent comme dans la maquette`() {
        val list = Recordings.merge(listOf("20261031101200", "20261030160502", "20261029090100").map { ListItem.Entry(it, 1, 1) },
            emptyList(), null, emptyMap(), Busy())
        assertEquals(listOf("Aujourd'hui", "Hier", "Jeudi 29 octobre"), Recordings.groupByDay(list, LocalDate.of(2026, 10, 31)).map { it.first })
        assertEquals("Récupération 1/2 · 40 %", Progress(ProgressKind.RECOVERY, 1, 2, 40).label())
        assertEquals("Envoi 1/3 · 90 %", Progress(ProgressKind.SEND, 1, 3, 90).label())
    }
}
