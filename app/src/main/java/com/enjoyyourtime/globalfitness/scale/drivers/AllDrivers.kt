package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry

/**
 * Every supported driver. Order is the tie-breaker between equally confident matches, so
 * specific drivers come before generic ones (same idea as openScale's handler factory).
 */
object AllDrivers {
    fun create(): List<ScaleDriver> = listOf(
        MiScale2Driver(),
        XiaomiS400Driver(),
        MiScale1Driver(),
    )

    fun registry(): ScaleRegistry = ScaleRegistry(create())
}
