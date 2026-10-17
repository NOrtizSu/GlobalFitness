// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.standard

import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.GattWearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableEvent
import com.enjoyyourtime.globalfitness.wearable.WearableException
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import com.enjoyyourtime.globalfitness.wearable.WearableSighting
import com.enjoyyourtime.globalfitness.wearable.WearableTransport
import java.util.UUID
import kotlinx.coroutines.withContext

/**
 * Any chest strap, band or watch exposing the standard Heart Rate service (0x180D), plus Battery
 * (0x180F) and Device Information (0x180A) when present; no key (Gadgetbridge
 * GenericHeartRateCoordinator / GenericHeartRateSupport, Polar H9 / H10 coordinators).
 *
 * [namePatterns] empty = the generic driver, which only claims advertised 0x180D with LOW
 * confidence (as Gadgetbridge, it is checked after every other driver).
 */
class StandardHeartRateDriver(
    override val id: String,
    override val displayName: String,
    override val brand: String,
    private val namePatterns: List<Regex> = emptyList(),
    override val keywords: List<String> = emptyList(),
    override val docUrl: String? = null,
) : WearableDriver {
    override val family = WearableFamily.STANDARD_BLE
    override val transport = WearableTransport.BLE_GATT
    override val authKey = AuthKeyKind.NONE
    override val features = setOf(WearableFeature.BATTERY, WearableFeature.DEVICE_INFO, WearableFeature.LIVE_HEART_RATE)

    override fun match(device: WearableSighting): MatchConfidence? {
        if (namePatterns.isNotEmpty()) {
            val name = device.trimmedName ?: return null
            return if (namePatterns.any { it.matches(name) }) MatchConfidence.HIGH else null
        }
        return if (device.hasService(StandardUuids.HEART_RATE_SERVICE)) MatchConfidence.LOW else null
    }

    override fun createSession(env: WearableSessionEnv): WearableSession = StandardHeartRateSession(env)

    companion object {
        const val GENERIC_ID = "standard_heart_rate"

        fun drivers(): List<StandardHeartRateDriver> = listOf(
            StandardHeartRateDriver("polar_h10", "Polar H10", "Polar", listOf(Regex("^Polar H10.*")), listOf("chest strap", "banda pectoral"), "https://gadgetbridge.org/gadgets/wearables/polar/#device__polar_h10"),
            StandardHeartRateDriver("polar_h9", "Polar H9", "Polar", listOf(Regex("Polar H9( \\w+)?$")), listOf("chest strap", "banda pectoral"), "https://gadgetbridge.org/gadgets/wearables/polar/#device__polar_h9"),
            StandardHeartRateDriver(
                GENERIC_ID,
                "Standard Bluetooth heart rate sensor",
                "Bluetooth",
                keywords = listOf("heart rate", "hrs", "0x180d", "chest strap", "pulsómetro", "banda pectoral", "generic", "genérico"),
                docUrl = "https://gadgetbridge.org/gadgets/generic-support/",
            ),
        )
    }
}

/** Heart rate, battery and device information over the standard services. */
class StandardHeartRateSession(env: WearableSessionEnv) : GattWearableSession(env, requestedMtu = 23) {
    override val label = "BLE HRS"
    private var liveActive = false

    override suspend fun onLinkReady() {
        val hrService = serviceOf(StandardUuids.HEART_RATE_MEASUREMENT)
            ?: throw WearableException(WearableFailure.UNSUPPORTED_DEVICE, "no heart rate measurement characteristic")
        enableNotifications(hrService, StandardUuids.HEART_RATE_MEASUREMENT)
        if (hasCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)) {
            enableNotifications(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)
        }
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            StandardUuids.HEART_RATE_MEASUREMENT -> {
                if (!liveActive) return
                val m = StandardGatt.parseHeartRate(value) ?: return
                emit(WearableEvent.HeartRate(if (m.isValid) m.bpm else 0, m.rrIntervalsMs, m.sensorContact, m.energyExpendedKj))
            }
            StandardUuids.BATTERY_LEVEL -> StandardGatt.parseBatteryLevel(value)?.let { emit(WearableEvent.Battery(it)) }
        }
    }

    override suspend fun refreshStatus(includeInfo: Boolean) = withContext(serial) {
        if (includeInfo) emit(WearableEvent.Info(readDeviceInformation()))
        if (hasCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)) {
            read(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)
                ?.let { StandardGatt.parseBatteryLevel(it) }
                ?.let { emit(WearableEvent.Battery(it)) }
        }
        Unit
    }

    override suspend fun setLiveHeartRate(enabled: Boolean) = withContext(serial) {
        // The sensor streams as soon as notifications are on; readings are only reported while wanted.
        liveActive = enabled
    }
}
