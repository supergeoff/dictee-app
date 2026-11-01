package top.supergeoff.dictee

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** L'application reconnaît le rôle d'une caractéristique quand son UUID contient l'un des repères du rôle : le boîtier testé montre les repères B0B, et elle accepte aussi les autres. */
private enum class Role(val tokens: List<String>) {
    WRITE(listOf("0011202a", "b0b1", "e8a1")),
    COMMAND(listOf("0011203a", "b0b2", "e8a2")),
    FILE_AUDIO(listOf("0011204a", "b0b4", "e8a4")),
    RECORD_AUDIO(listOf("0011201a", "b0b3", "e8a3")),
    ;

    fun matches(uuid: UUID): Boolean = uuid.toString().lowercase().let { u -> tokens.any { u.contains(it) } }
}

/**
 * Liaison GATT Android vers le MECHEN V05 (docs/protocole-v05.md, section « Caractéristiques GATT »).
 * [onClosed] est appelé une seule fois, quand le GATT signale la coupure ou quand [close] est appelé.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(
    private val journal: (String) -> Unit,
    private val onClosed: () -> Unit,
) : Link {
    private val channel = Channel<Notification>(Channel.UNLIMITED)
    private val closedReported = AtomicBoolean(false)
    override val incoming: ReceiveChannel<Notification> get() = channel

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val sources = mutableMapOf<UUID, Pair<LinkChannel, String>>()

    @Volatile private var op: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Int>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                journal("liaison Bluetooth coupée (statut $status)")
                connected.complete(-1)
                op?.complete(-1)
                channel.close()
                reportClosed()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            journal("MTU $mtu (statut $status)")
            op?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            op?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            op?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            op?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c, value)
        }

        @Deprecated("Remplacé par la variante avec la valeur à partir d'Android 13")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // Android 13 et plus appellent aussi la variante ci-dessus : on ne traite ici que les versions antérieures.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) deliver(c, c.value ?: return)
        }
    }

    private fun reportClosed() {
        if (closedReported.compareAndSet(false, true)) onClosed()
    }

    private fun deliver(c: BluetoothGattCharacteristic, value: ByteArray) {
        val (ch, source) = sources[c.uuid] ?: return
        channel.trySend(Notification(ch, value.copyOf(), source))
    }

    /** Lance une opération GATT et attend son rappel. */
    private suspend fun gattOp(what: String, timeoutMs: Long = 5_000, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        op = d
        if (!start()) {
            op = null
            throw SessionException("$what impossible")
        }
        val status = withTimeoutOrNull(timeoutMs) { d.await() } ?: throw SessionException("$what : pas de réponse")
        op = null
        if (status == -1) throw SessionException("liaison Bluetooth coupée pendant : $what")
        return status
    }

    private suspend fun open(context: Context, address: String) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw SessionException("Bluetooth indisponible")
        if (!adapter.isEnabled) throw SessionException("Bluetooth désactivé")
        val device = adapter.getRemoteDevice(address)
        journal("connexion à $address")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw SessionException("boîtier injoignable")
        gatt = g
        val state = withTimeoutOrNull(15_000) { connected.await() }
        if (state == null || state == -1) throw SessionException("boîtier injoignable")

        runCatching { gattOp("demande de MTU") { g.requestMtu(247) } }
            .onFailure { journal("MTU non négocié : ${it.message}") }
        val discovered = gattOp("découverte des services", 10_000) { g.discoverServices() }
        if (discovered != BluetoothGatt.GATT_SUCCESS) throw SessionException("découverte des services refusée ($discovered)")

        val toNotify = mutableListOf<BluetoothGattCharacteristic>()
        for (service in g.services) {
            for (c in service.characteristics) {
                val role = Role.entries.firstOrNull { it.matches(c.uuid) }
                journal("caractéristique ${c.uuid} handle 0x%04x propriétés 0x%02x rôle %s".format(c.instanceId, c.properties, role?.name ?: "-"))
                if (role == null) continue
                val source = role.tokens.first()
                when (role) {
                    Role.WRITE -> if (writeChar == null) writeChar = c
                    Role.COMMAND -> sources[c.uuid] = LinkChannel.COMMAND to source
                    Role.FILE_AUDIO -> sources[c.uuid] = LinkChannel.FILE_AUDIO to source
                    Role.RECORD_AUDIO -> sources[c.uuid] = LinkChannel.RECORD_AUDIO to source
                }
                if (role != Role.WRITE && c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) toNotify += c
            }
        }
        if (writeChar == null || sources.values.none { it.first == LinkChannel.COMMAND }) {
            throw SessionException("caractéristiques du boîtier introuvables")
        }
        for (c in toNotify) enableNotifications(g, c)
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = gattOp("activation des notifications de ${c.uuid}") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        journal("notifications activées sur ${c.uuid} (handle 0x%04x, statut %d)".format(c.instanceId, status))
    }

    @Suppress("DEPRECATION")
    override suspend fun write(frame: ByteArray) {
        val g = gatt ?: throw SessionException("liaison fermée")
        val c = writeChar ?: throw SessionException("caractéristique d'écriture absente")
        gattOp("écriture d'une commande") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = frame
                g.writeCharacteristic(c)
            }
        }
    }

    override suspend fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        channel.close()
        reportClosed()
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Ouvre la liaison avec le boîtier mémorisé : connexion, MTU, services et notifications. [onClosed] reçoit la coupure de la liaison. */
        suspend fun connect(context: Context, address: String, journal: (String) -> Unit, onClosed: () -> Unit = {}): BleLink {
            val link = BleLink(journal, onClosed)
            try {
                link.open(context, address)
            } catch (e: Exception) {
                link.close()
                throw e
            }
            return link
        }
    }
}

private const val NAME_PREFIX = "MECHEN"

/** L'application accepte aussi un appareil dont l'annonce porte l'un de ces identifiants fabricant (octets 16 11 ou 24 A7). */
private val MANUFACTURER_IDS = listOf(0x1116, 0xA724)

/**
 * Boîtiers MECHEN déjà connectés au téléphone. Un boîtier connecté ne s'annonce plus.
 * L'app ferme sa propre liaison avant de chercher ([AppModel.closeForSearch]) : un boîtier encore connecté l'est par une autre app.
 */
@SuppressLint("MissingPermission")
fun connectedBoxes(context: Context): List<Pair<String, String>> = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        .mapNotNull { d -> d.name?.takeIf { it.startsWith(NAME_PREFIX) }?.let { it to d.address } }
}.getOrDefault(emptyList())

/** Recherche des boîtiers : nom commençant par MECHEN, ou données fabricant du boîtier. */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private var callback: ScanCallback? = null

    /** Nombre d'appareils Bluetooth distincts vus pendant la recherche, pour le diagnostic. */
    var seenCount = 0
        private set

    /** Lance la recherche. Renvoie false si le Bluetooth est indisponible. */
    fun start(onFound: (name: String, address: String) -> Unit): Boolean {
        val s = scanner ?: return false
        val seen = mutableSetOf<String>()
        val found = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (seen.add(address)) seenCount = seen.size
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name
                val maker = MANUFACTURER_IDS.any { record?.getManufacturerSpecificData(it) != null }
                if ((name?.startsWith(NAME_PREFIX) == true || maker) && found.add(address)) onFound(name ?: NAME_PREFIX, address)
            }
        }
        callback = cb
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return true
    }

    fun stop() {
        callback?.let { scanner?.stopScan(it) }
        callback = null
    }
}
