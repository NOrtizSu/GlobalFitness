package com.enjoyyourtime.globalfitness.scale

/**
 * Mutable accumulator a driver fills while decoding (possibly across several packets), then
 * publishes. Mirrors the shape of openScale's measurement object so ported protocol code stays
 * close to the original. Values that are null, non-finite or not positive count as "not reported".
 */
class ScaleSample {
    /** User slot / id reported by the scale; [UNKNOWN_USER] when the scale has none. */
    var userId: Int = UNKNOWN_USER

    /** Scale clock in epoch millis, when the protocol carries one. */
    var dateTime: Long? = null

    var weightKg: Float? = null
    var fatPercent: Float? = null
    var waterPercent: Float? = null
    var musclePercent: Float? = null
    var muscleKg: Float? = null
    var lbmKg: Float? = null
    var boneKg: Float? = null
    var visceralFat: Float? = null
    var bmrKcal: Float? = null
    var proteinPercent: Float? = null
    var metabolicAge: Float? = null

    /** Skeletal muscle % (not the total muscle of [musclePercent]). */
    var skeletalMusclePercent: Float? = null
    var impedance: Float? = null
    var impedanceLow: Float? = null
    var heartRate: Int? = null

    /** Set when the driver computed the composition itself with a formula library. */
    var computedWith: FormulaLib? = null

    /** Library for (re)computing composition from [impedance] when it differs per frame variant. */
    var impedanceLib: FormulaLib? = null

    /** Marks samples downloaded from the scale memory. */
    var fromHistory: Boolean = false

    fun hasWeight(): Boolean = usable(weightKg)

    /** Fill-the-gaps merge for multi-packet protocols: values already present win. */
    fun mergeWith(other: ScaleSample): ScaleSample = apply {
        if (!usable(weightKg)) weightKg = other.weightKg
        if (!usable(fatPercent)) fatPercent = other.fatPercent
        if (!usable(waterPercent)) waterPercent = other.waterPercent
        if (!usable(musclePercent)) musclePercent = other.musclePercent
        if (!usable(muscleKg)) muscleKg = other.muscleKg
        if (!usable(lbmKg)) lbmKg = other.lbmKg
        if (!usable(boneKg)) boneKg = other.boneKg
        if (!usable(visceralFat)) visceralFat = other.visceralFat
        if (!usable(bmrKcal)) bmrKcal = other.bmrKcal
        if (!usable(proteinPercent)) proteinPercent = other.proteinPercent
        if (!usable(metabolicAge)) metabolicAge = other.metabolicAge
        if (!usable(skeletalMusclePercent)) skeletalMusclePercent = other.skeletalMusclePercent
        if (!usable(impedance)) impedance = other.impedance
        if (!usable(impedanceLow)) impedanceLow = other.impedanceLow
        if ((heartRate ?: 0) <= 0) heartRate = other.heartRate
        if (computedWith == null) computedWith = other.computedWith
        if (impedanceLib == null) impedanceLib = other.impedanceLib
        if (other.userId != UNKNOWN_USER && (userId == UNKNOWN_USER || userId == -1)) userId = other.userId
        if (dateTime == null) dateTime = other.dateTime
        fromHistory = fromHistory || other.fromHistory
    }

    fun copy(): ScaleSample = ScaleSample().also { it.mergeWith(this); it.userId = userId; it.dateTime = dateTime }

    /**
     * Composition values present in this sample (null when none). Scale-reported values outside
     * human ranges (e.g. SIG "unsuccessful" 0xFFFF fields) are dropped.
     */
    fun composition(): DeviceComposition? = DeviceComposition.of(
        DeviceComposition(
            fatPercent = d(fatPercent)?.takeIf { it in 2.0..75.0 },
            waterPercent = d(waterPercent)?.takeIf { it in 20.0..80.0 },
            musclePercent = d(musclePercent)?.takeIf { it in 10.0..90.0 },
            muscleKg = d(muscleKg),
            boneKg = d(boneKg)?.takeIf { it in 0.5..8.0 },
            visceralFat = d(visceralFat),
            bmrKcal = d(bmrKcal),
            lbmKg = d(lbmKg),
            proteinPercent = d(proteinPercent),
            metabolicAge = d(metabolicAge),
            skeletalMusclePercent = d(skeletalMusclePercent)?.takeIf { it in 5.0..70.0 },
        ),
    )

    /**
     * Converts to a [ScaleReading] for the device in [env]. Returns null without a usable weight.
     * Drivers of multi-user scales filter out other users' weighings before publishing.
     * [formulaLib] is the driver's impedance library, used when the scale reports impedance only.
     */
    fun toReading(env: DriverEnv, formulaLib: FormulaLib?, receivedAt: Long = env.wallClock()): ScaleReading? {
        val w = weightKg ?: return null
        if (!w.isFinite() || w <= 0f || w > MAX_WEIGHT_KG) return null
        val weight = w.toDouble()
        val scaleTime = dateTime
        val plausible = ScaleReading.plausibleTime(scaleTime, receivedAt)
        // A weighing taken just now keeps the phone's time when the scale clock is clearly off.
        val measuredAt = if (!fromHistory && scaleTime != null && kotlin.math.abs(scaleTime - receivedAt) > ScaleReading.LIVE_CLOCK_TOLERANCE_MS) {
            receivedAt
        } else {
            plausible
        }
        val comp = composition()
        val imp = impedance?.takeIf { it.isFinite() && it > 0f }?.let { Math.round(it) }
        val impLow = impedanceLow?.takeIf { it.isFinite() && it > 0f }?.let { Math.round(it) }
        val lib = computedWith ?: impedanceLib ?: formulaLib
        val source = when {
            comp != null && computedWith != null -> CompositionSource.Formula(computedWith!!)
            comp != null -> CompositionSource.Device
            imp != null && lib != null -> CompositionSource.Formula(lib)
            else -> CompositionSource.None
        }
        val addr = env.address.orEmpty()
        // The raw scale timestamp identifies a record even when the scale clock is wrong, so a
        // history downloaded again is recognised; without one, each reception is its own weighing.
        val keyTime = if (scaleTime != null) scaleTime / 1000 else receivedAt
        val key = "${env.driverId}|$addr|$keyTime|${ScaleReading.grams(weight)}"
        return ScaleReading(
            weightKg = weight,
            impedance = imp,
            composition = comp,
            impedanceLow = impLow,
            measuredAtMillis = measuredAt,
            receivedAtMillis = receivedAt,
            dedupeKey = key,
            compositionSource = source,
            formulaLib = if (imp != null) lib else null,
            driverId = env.driverId,
            deviceAddress = env.address,
            userSlot = userId.takeIf { it != UNKNOWN_USER && it != -1 },
            fromHistory = fromHistory,
        )
    }

    override fun toString(): String =
        "ScaleSample(user=$userId, t=$dateTime, w=$weightKg, fat=$fatPercent, water=$waterPercent, " +
            "muscle=$musclePercent, bone=$boneKg, lbm=$lbmKg, visc=$visceralFat, bmr=$bmrKcal, imp=$impedance)"

    companion object {
        const val UNKNOWN_USER = 0xFF
        const val MAX_WEIGHT_KG = 400f

        private fun usable(v: Float?): Boolean = v != null && v.isFinite() && v > 0f
        private fun d(v: Float?): Double? = v?.takeIf { usable(it) }?.toDouble()
    }
}
