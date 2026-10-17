package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.band.HeartRateSample
import com.enjoyyourtime.globalfitness.band.RecordedHeartRate
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiModels
import java.util.Locale
import java.util.UUID

/** One device's live heart rate samples (band.db), for a chart. [name] null = unknown / removed device. */
data class DeviceHeartRate(
    val deviceAddress: String?,
    val name: String?,
    val driverId: String?,
    val samples: List<HeartRateSample>,
)

object WearableHeartRate {
    /**
     * Groups stored samples by recording device (rows of the original Smart Band 9 mode have no
     * driver but do have the address), named after the configured device with that address.
     * Ordered by the first sample of each device.
     */
    fun groupByDevice(rows: List<RecordedHeartRate>, configured: List<ConfiguredWearable>): List<DeviceHeartRate> =
        rows.groupBy { it.deviceAddress?.uppercase(Locale.ROOT).orEmpty() }
            .map { (address, group) ->
                val device = configured.firstOrNull { address.isNotEmpty() && it.matchesAddress(address) }
                DeviceHeartRate(
                    deviceAddress = address.ifEmpty { null },
                    name = device?.name,
                    driverId = device?.driverId ?: group.firstNotNullOfOrNull { it.driverId },
                    samples = group.map { it.sample }.sortedBy { it.timestampMs },
                )
            }
            .sortedBy { it.samples.first().timestampMs }
}

/** Moves the original Smart Band 9 mode's selection into a configured wearable (once). */
object LegacyBandImport {
    /**
     * The configured wearable for the band selected in the original mode: the Xiaomi driver its
     * name matches, the Smart Band 9 otherwise (that mode only listed Xiaomi bands).
     */
    fun configuredFor(
        registry: WearableRegistry,
        address: String,
        name: String,
        syncTimeOnConnect: Boolean,
        nowMillis: Long,
        id: String = UUID.randomUUID().toString(),
    ): ConfiguredWearable {
        val matched = registry.detect(WearableSighting(address, name, bonded = true))
            .firstOrNull { it.driver.family == WearableFamily.XIAOMI }?.driver
        val driverId = matched?.id ?: XiaomiModels.BAND_9_ID
        return ConfiguredWearable(
            id = id,
            driverId = driverId,
            address = address.uppercase(Locale.ROOT),
            name = name,
            config = if (syncTimeOnConnect) emptyMap() else mapOf(ConfiguredWearable.CONFIG_SYNC_TIME to "0"),
            addedAt = nowMillis,
            fromSuggestion = false,
        )
    }
}
