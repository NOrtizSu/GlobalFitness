package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.data.Sex
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Shared builders for scale driver tests. */
object ScaleTestSupport {
    const val ADDRESS = "C0:FF:EE:12:34:56"

    fun hex(value: String): ByteArray = Bytes.fromHex(value)

    fun adv(
        name: String? = null,
        services: List<UUID> = emptyList(),
        serviceData: Map<UUID, ByteArray> = emptyMap(),
        manufacturer: Map<Int, ByteArray> = emptyMap(),
        address: String = ADDRESS,
        rssi: Int = -60,
        receivedAt: Long = 1_800_000_000_000L,
    ) = AdvertisementInfo(address, name, rssi, services, serviceData, manufacturer, null, receivedAt)

    /** 35-year-old, 180 cm man (same reference user as openScale's handler tests). */
    fun ctx(
        sex: Sex = Sex.MALE,
        heightCm: Float = 180f,
        birthYear: Int = 1990,
        userSlot: Int = 1,
        lastWeight: Float? = 80f,
    ) = ScaleContext(
        appUserId = 1,
        name = "Test",
        sex = sex,
        birthDate = LocalDate.of(birthYear, 1, 1),
        heightCm = heightCm,
        lastWeightKg = lastWeight,
        goalWeightKg = null,
        userSlot = userSlot,
    )

    class Clock(var now: Long = 1_800_000_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    fun env(
        driverId: String,
        clock: Clock = Clock(),
        ctx: ScaleContext = ctx(),
        store: DriverStore = MapDriverStore(),
        address: String? = ADDRESS,
        messages: MutableList<String> = mutableListOf(),
    ) = DriverEnv(
        ctx = ctx,
        store = store,
        driverId = driverId,
        address = address,
        wallClock = { clock.now },
        elapsedClock = { clock.now },
        messages = { messages += it },
    )

    fun epochMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDate.of(y, mo, d).atTime(h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Records everything a [GattProtocol] asks for; timers are fired manually. */
class FakeGattIo(
    /** Characteristics that exist; null = every characteristic exists. */
    private val available: Set<Pair<UUID, UUID>>? = null,
) : GattIo {
    sealed interface Op {
        data class Notify(val service: UUID, val characteristic: UUID) : Op
        class Write(val service: UUID, val characteristic: UUID, val payload: ByteArray, val withResponse: Boolean) : Op {
            override fun toString(): String = "Write($characteristic, ${Bytes.toHex(payload)})"
        }
        data class Read(val service: UUID, val characteristic: UUID) : Op
        data object Disconnect : Op
    }

    val ops = mutableListOf<Op>()
    val readings = mutableListOf<ScaleReading>()
    val lives = mutableListOf<Double>()
    val messages = mutableListOf<String>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()

    val writes: List<Op.Write> get() = ops.filterIsInstance<Op.Write>()
    val writtenHex: List<String> get() = writes.map { Bytes.toHex(it.payload) }
    val notified: List<UUID> get() = ops.filterIsInstance<Op.Notify>().map { it.characteristic }
    val disconnected: Boolean get() = ops.any { it == Op.Disconnect }

    override fun setNotifyOn(service: UUID, characteristic: UUID) {
        ops += Op.Notify(service, characteristic)
    }

    override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
        ops += Op.Write(service, characteristic, payload.copyOf(), withResponse)
    }

    override fun read(service: UUID, characteristic: UUID) {
        ops += Op.Read(service, characteristic)
    }

    override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        available == null || (service to characteristic) in available

    /** GATT tree reported to protocols that enumerate it. */
    var tree: List<GattCharInfo> = emptyList()

    override fun characteristics(): List<GattCharInfo> = tree

    override fun disconnect() {
        ops += Op.Disconnect
    }

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val entry = delayMs to action
        timers += entry
        return Cancellable { timers.remove(entry) }
    }

    /** Runs (and removes) every pending timer. */
    fun fireTimers() {
        val pending = timers.toList()
        timers.clear()
        pending.forEach { it.second() }
    }

    val pendingTimers: Int get() = timers.size

    override fun publish(reading: ScaleReading) {
        readings += reading
    }

    override fun live(weightKg: Double) {
        lives += weightKg
    }

    override fun message(text: String) {
        messages += text
    }

    fun clearOps() = ops.clear()
}

/** Drives one [GattProtocol] of [driver] with a [FakeGattIo]. */
class ProtocolHarness(
    driver: GattScaleDriver,
    adv: AdvertisementInfo? = null,
    val io: FakeGattIo = FakeGattIo(),
    val clock: ScaleTestSupport.Clock = ScaleTestSupport.Clock(),
    ctx: ScaleContext = ScaleTestSupport.ctx(),
    val store: MapDriverStore = MapDriverStore(),
    address: String? = ScaleTestSupport.ADDRESS,
) {
    val env: DriverEnv = ScaleTestSupport.env(driver.id, clock, ctx, store, address)
    val protocol: GattProtocol = driver.createProtocol(adv).also { it.attach(io, env) }

    fun connect(): ProtocolHarness = apply { protocol.onConnected() }

    fun notify(characteristic: UUID, hex: String) = protocol.onNotification(characteristic, Bytes.fromHex(hex))

    fun notify(characteristic: UUID, bytes: ByteArray) = protocol.onNotification(characteristic, bytes)

    val readings: List<ScaleReading> get() = io.readings
}
