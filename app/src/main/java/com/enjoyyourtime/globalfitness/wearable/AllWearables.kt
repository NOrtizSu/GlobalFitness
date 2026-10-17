package com.enjoyyourtime.globalfitness.wearable

import com.enjoyyourtime.globalfitness.wearable.banglejs.BangleJsDriver
import com.enjoyyourtime.globalfitness.wearable.huami.HuamiModels
import com.enjoyyourtime.globalfitness.wearable.pinetime.PineTimeDriver
import com.enjoyyourtime.globalfitness.wearable.standard.StandardHeartRateDriver
import com.enjoyyourtime.globalfitness.wearable.xiaomi.XiaomiModels
import com.enjoyyourtime.globalfitness.wearable.zeppos.ZeppOsModels

/**
 * Every supported wearable driver. Order is the tie-breaker between equally confident matches:
 * model-specific drivers first, the generic heart rate driver last.
 */
object AllWearables {
    fun create(): List<WearableDriver> =
        XiaomiModels.drivers() +
            ZeppOsModels.drivers() +
            HuamiModels.drivers() +
            listOf(PineTimeDriver(), BangleJsDriver()) +
            StandardHeartRateDriver.drivers()

    fun registry(): WearableRegistry = WearableRegistry(create())
}
