package com.enjoyyourtime.globalfitness.scale

import java.util.Locale
import java.util.UUID

/**
 * Platform-independent snapshot of one BLE advertisement (built from a `ScanResult` by the
 * scanner). Everything a driver needs to recognise a scale or decode a broadcast lives here, so
 * matching and passive parsing are plain Kotlin and unit-testable.
 */
class AdvertisementInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<UUID> = emptyList(),
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    /** Manufacturer specific data keyed by the 16-bit company id (as Android's ScanRecord splits it). */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    /** The raw advertising + scan response bytes, when available. */
    val rawBytes: ByteArray? = null,
    val receivedAtMillis: Long = 0L,
) {
    /** Advertised name, trimmed; empty when the device has none. */
    val nameOrEmpty: String get() = name?.trim().orEmpty()

    /** Upper-cased name for case-insensitive matching. */
    val upperName: String get() = nameOrEmpty.uppercase(Locale.ROOT)

    fun hasService(uuid: UUID): Boolean = uuid in serviceUuids || serviceData.containsKey(uuid)

    fun hasService16(short: Int): Boolean = hasService(Bytes.uuid16(short))

    fun serviceData16(short: Int): ByteArray? = serviceData[Bytes.uuid16(short)]

    fun manufacturer(companyId: Int): ByteArray? = manufacturerData[companyId]

    /**
     * Manufacturer records as the raw AD payload (company id little-endian followed by the data),
     * which is how several broadcast protocols are documented.
     */
    fun manufacturerRecords(): List<ByteArray> = manufacturerData.map { (id, data) ->
        byteArrayOf((id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte()) + data
    }

    fun copy(
        address: String = this.address,
        name: String? = this.name,
        rssi: Int = this.rssi,
        serviceUuids: List<UUID> = this.serviceUuids,
        serviceData: Map<UUID, ByteArray> = this.serviceData,
        manufacturerData: Map<Int, ByteArray> = this.manufacturerData,
        rawBytes: ByteArray? = this.rawBytes,
        receivedAtMillis: Long = this.receivedAtMillis,
    ) = AdvertisementInfo(address, name, rssi, serviceUuids, serviceData, manufacturerData, rawBytes, receivedAtMillis)

    override fun toString(): String = buildString {
        append("Adv(").append(address).append(", name=").append(name).append(", rssi=").append(rssi)
        if (serviceUuids.isNotEmpty()) append(", services=").append(serviceUuids)
        serviceData.forEach { (k, v) -> append(", sd[").append(k).append("]=").append(Bytes.toHex(v)) }
        manufacturerData.forEach { (k, v) ->
            append(", md[").append(String.format(Locale.ROOT, "0x%04X", k)).append("]=").append(Bytes.toHex(v))
        }
        append(')')
    }
}
