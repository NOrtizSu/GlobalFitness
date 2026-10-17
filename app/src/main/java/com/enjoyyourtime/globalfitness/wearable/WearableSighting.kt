package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import java.util.Locale
import java.util.UUID

/** Bluetooth device type as Android reports it for bonded devices. */
enum class BtDeviceType { UNKNOWN, CLASSIC, LE, DUAL }

/**
 * A device the phone knows about: a BLE advertisement and/or an entry of the bonded devices list
 * (Mi Fitness / Zepp bond their devices with the phone; Bluetooth Classic devices never advertise
 * over BLE). Plain Kotlin so matching is unit-testable.
 */
class WearableSighting(
    val address: String,
    val name: String?,
    /** Last advertisement RSSI (dBm); null for a bonded device not seen advertising. */
    val rssi: Int? = null,
    val bonded: Boolean = false,
    val deviceType: BtDeviceType = BtDeviceType.UNKNOWN,
    val serviceUuids: List<UUID> = emptyList(),
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val receivedAtMillis: Long = 0L,
) {
    /** Trimmed name, null when empty. */
    val trimmedName: String? = name?.trim()?.takeIf { it.isNotEmpty() }

    val upperName: String = trimmedName.orEmpty().uppercase(Locale.ROOT)

    fun hasService(uuid: UUID): Boolean = uuid in serviceUuids || serviceData.containsKey(uuid)

    fun hasService16(short: Int): Boolean = hasService(Bytes.uuid16(short))

    fun manufacturer(companyId: Int): ByteArray? = manufacturerData[companyId]

    override fun toString(): String = buildString {
        append("Sighting(").append(address).append(", name=").append(name)
        if (rssi != null) append(", rssi=").append(rssi)
        if (bonded) append(", bonded")
        if (deviceType != BtDeviceType.UNKNOWN) append(", ").append(deviceType)
        if (serviceUuids.isNotEmpty()) append(", services=").append(serviceUuids)
        manufacturerData.forEach { (k, v) ->
            append(", md[").append(String.format(Locale.ROOT, "0x%04X", k)).append("]=").append(Bytes.toHex(v))
        }
        append(')')
    }

    companion object {
        fun fromAdvertisement(adv: AdvertisementInfo, bonded: Boolean = false): WearableSighting = WearableSighting(
            address = adv.address,
            name = adv.name,
            rssi = adv.rssi,
            bonded = bonded,
            deviceType = BtDeviceType.LE,
            serviceUuids = adv.serviceUuids,
            serviceData = adv.serviceData,
            manufacturerData = adv.manufacturerData,
            receivedAtMillis = adv.receivedAtMillis,
        )

        fun bonded(address: String, name: String?, type: BtDeviceType, uuids: List<UUID> = emptyList()) =
            WearableSighting(address = address, name = name, bonded = true, deviceType = type, serviceUuids = uuids)
    }
}
