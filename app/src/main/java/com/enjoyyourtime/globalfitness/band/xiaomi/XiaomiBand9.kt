// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.band.xiaomi

import java.util.UUID

/** Xiaomi Smart Band 9 facts from Gadgetbridge's MiBand9Coordinator and XiaomiUuids. */
object XiaomiBand9 {
    /** Advertised/bonded name, e.g. "Xiaomi Smart Band 9 3A7F" (MiBand9Coordinator.getSupportedDeviceName). */
    val NAME_PATTERN = Regex("^Xiaomi Smart Band 9 [0-9A-F]{4}$")

    /** Other Xiaomi bands are listed too, flagged as untested with this protocol port. */
    private val XIAOMI_BAND_PREFIX = Regex("^(Xiaomi Smart Band|Redmi Smart Band|Mi Smart Band)", RegexOption.IGNORE_CASE)

    /**
     * The Band 9 is a Bluetooth Classic device (ConnectionType.BT_CLASSIC): the protocol runs over an
     * RFCOMM socket to the Serial Port Profile service (XiaomiUuids.UUID_SERVICE_SERIAL_PORT_PROFILE).
     */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    fun isBand9(name: String?): Boolean = name != null && NAME_PATTERN.matches(name.trim())

    fun isXiaomiBand(name: String?): Boolean = name != null && XIAOMI_BAND_PREFIX.containsMatchIn(name.trim())
}
