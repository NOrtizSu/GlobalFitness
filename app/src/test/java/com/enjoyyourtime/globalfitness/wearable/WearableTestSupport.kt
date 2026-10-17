package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.xiaomi.BandLink
import com.enjoyyourtime.globalfitness.band.xiaomi.CompanionInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.GattCharInfo
import com.enjoyyourtime.globalfitness.scale.GattException
import com.enjoyyourtime.globalfitness.scale.GattLink
import com.enjoyyourtime.globalfitness.scale.GattNotification
import com.enjoyyourtime.globalfitness.scale.MapDriverStore
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

fun hex(s: String): ByteArray = Bytes.fromHex(s.replace(" ", ""))

fun ByteArray.hex(): String = Bytes.toHex(this)

/**
 * A simulated BLE peripheral behind the [GattLink] interface: a GATT tree, values to read, and a
 * [onWrite] hook through which a test plays the device (answering with [notify]).
 */
class FakeGattDevice(
    override val address: String = "C0:FF:EE:00:00:01",
    chars: List<Pair<UUID, UUID>>,
    private val mtu: Int = 247,
) : GattLink {
    private val tree = chars.map { (s, c) -> GattCharInfo(s, c, canNotify = true, canWrite = true) }
    private val connected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = connected
    private val channel = Channel<GattNotification>(Channel.UNLIMITED)
    override val notifications: Flow<GattNotification> = channel.receiveAsFlow()

    class Write(val service: UUID, val characteristic: UUID, val value: ByteArray) {
        override fun toString(): String = "$characteristic ${value.hex()}"
    }

    val writes: MutableList<Write> = Collections.synchronizedList(mutableListOf())
    val subscribed: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
    val readValues = HashMap<UUID, ByteArray>()

    @Volatile
    var failConnect = false

    @Volatile
    var closed = false

    /** The device's reaction to a write. */
    @Volatile
    var onWrite: (Write) -> Unit = {}

    fun notify(characteristic: UUID, value: ByteArray) {
        channel.trySend(GattNotification(characteristic, value))
    }

    /** Simulates the device dropping the connection. */
    fun drop() {
        connected.value = false
    }

    fun writesTo(characteristic: UUID): List<String> = writes.toList().filter { it.characteristic == characteristic }.map { it.value.hex() }

    override suspend fun connect() {
        if (failConnect) throw GattException(ScaleNotice(ScaleNotice.Kind.CONNECT_FAILED))
        connected.value = true
    }

    override suspend fun discoverServices() = Unit

    override suspend fun requestMtu(mtu: Int): Int = minOf(mtu, this.mtu)

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = tree.any { it.service == service && it.characteristic == characteristic }

    override fun characteristics(): List<GattCharInfo> = tree

    override suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
        if (!hasCharacteristic(service, characteristic)) return false
        subscribed += characteristic
        return true
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray, withResponse: Boolean): Boolean {
        if (!hasCharacteristic(service, characteristic)) return false
        val w = Write(service, characteristic, value.copyOf())
        writes += w
        onWrite(w)
        return true
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray? =
        if (hasCharacteristic(service, characteristic)) readValues[characteristic] else null

    override fun close() {
        closed = true
        connected.value = false
        channel.close()
    }
}

class FakeLinks(var gattDevice: GattLink? = null, var sppLink: BandLink? = null) : WearableLinkFactory {
    override fun gatt(address: String): GattLink = gattDevice ?: error("no GATT device")

    override suspend fun spp(address: String): BandLink = sppLink ?: throw WearableException(WearableFailure.CONNECT_FAILED)
}

val FAST_TIMEOUTS = WearableTimeouts(connect = 2_000, auth = 1_500, request = 1_500, optionalStep = 300)

fun testEnv(
    scope: CoroutineScope,
    links: WearableLinkFactory,
    authKey: String? = null,
    store: MapDriverStore = MapDriverStore(),
    config: Map<String, String> = emptyMap(),
    log: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    timeouts: WearableTimeouts = FAST_TIMEOUTS,
) = WearableSessionEnv(
    address = "C0:FF:EE:00:00:01",
    name = "Test device",
    authKey = authKey,
    config = config,
    store = store,
    companion = CompanionInfo(34, "Pixel 8", "ES"),
    scope = scope,
    links = links,
    log = { log += it },
    clock = { 1_800_000_000_000L },
    timeouts = timeouts,
)
