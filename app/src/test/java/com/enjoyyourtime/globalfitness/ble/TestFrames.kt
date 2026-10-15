package com.enjoyyourtime.globalfitness.ble

/** Builds raw 13-byte Mi Scale 2 payloads for tests. */
object TestFrames {
    const val LIVE = 0x00
    const val STABLE = 0x24
    const val STABLE_IMPEDANCE = 0x26
    const val LOAD_REMOVED = 0x80
    const val STABLE_LOAD_REMOVED = 0xA4
    const val STABLE_IMPEDANCE_LOAD_REMOVED = 0xA6

    fun bytes(
        ctrl1: Int,
        raw: Int,
        impedance: Int = 0,
        ctrl0: Int = 0x02,
        year: Int = 2026,
        month: Int = 10,
        day: Int = 15,
        hour: Int = 8,
        minute: Int = 30,
        second: Int = 45,
    ): ByteArray = byteArrayOf(
        ctrl0.toByte(),
        ctrl1.toByte(),
        (year and 0xFF).toByte(),
        (year shr 8).toByte(),
        month.toByte(),
        day.toByte(),
        hour.toByte(),
        minute.toByte(),
        second.toByte(),
        (impedance and 0xFF).toByte(),
        (impedance shr 8).toByte(),
        (raw and 0xFF).toByte(),
        (raw shr 8).toByte(),
    )

    fun frame(ctrl1: Int, raw: Int, impedance: Int = 0, second: Int = 45): ScaleFrame =
        requireNotNull(MiScaleParser.parse(bytes(ctrl1 = ctrl1, raw = raw, impedance = impedance, second = second)))
}
