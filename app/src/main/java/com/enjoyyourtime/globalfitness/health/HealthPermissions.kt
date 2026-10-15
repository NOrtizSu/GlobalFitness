package com.enjoyyourtime.globalfitness.health

/**
 * Health Connect data types used by the app. Permission strings are the public Health Connect
 * names (`android.permission.health.*`), kept here as plain strings so the permission-set logic
 * stays pure Kotlin. They must match the `<uses-permission>` entries in the manifest.
 */
enum class HealthDataType(val label: String, val readPermission: String?, val writePermission: String?) {
    STEPS("Pasos", read("STEPS"), null),
    DISTANCE("Distancia", read("DISTANCE"), null),
    ACTIVE_CALORIES("Calorías activas", read("ACTIVE_CALORIES_BURNED"), null),
    TOTAL_CALORIES("Calorías totales", read("TOTAL_CALORIES_BURNED"), null),
    HEART_RATE("Pulso", read("HEART_RATE"), null),
    RESTING_HEART_RATE("Pulso en reposo", read("RESTING_HEART_RATE"), null),
    HEART_RATE_VARIABILITY("Variabilidad del pulso", read("HEART_RATE_VARIABILITY"), null),
    SLEEP("Sueño", read("SLEEP"), null),
    OXYGEN_SATURATION("Oxígeno en sangre", read("OXYGEN_SATURATION"), null),
    EXERCISE("Ejercicio", read("EXERCISE"), null),
    RESPIRATORY_RATE("Frecuencia respiratoria", read("RESPIRATORY_RATE"), null),
    WEIGHT("Peso", read("WEIGHT"), write("WEIGHT")),
    BODY_FAT("Grasa corporal", read("BODY_FAT"), write("BODY_FAT")),
    BONE_MASS("Masa ósea", null, write("BONE_MASS")),
    LEAN_BODY_MASS("Masa magra", null, write("LEAN_BODY_MASS")),
    BASAL_METABOLIC_RATE("Metabolismo basal", null, write("BASAL_METABOLIC_RATE")),
    BODY_WATER_MASS("Agua corporal", null, write("BODY_WATER_MASS")),
}

private fun read(name: String) = "android.permission.health.READ_$name"
private fun write(name: String) = "android.permission.health.WRITE_$name"

/** Pure permission-set logic: what to request and what each partial grant enables. */
object HealthPermissions {
    /** Lets the app read data older than 30 days before the first grant (feature-gated). */
    const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /** Initial import window without / with the history permission. */
    const val DEFAULT_WINDOW_DAYS = 30
    const val HISTORY_WINDOW_DAYS = 365

    val readPermissions: Set<String> = HealthDataType.entries.mapNotNull { it.readPermission }.toSet()
    val writePermissions: Set<String> = HealthDataType.entries.mapNotNull { it.writePermission }.toSet()

    /** Types the app reads from wearables (weight/body fat are read only to show other sources). */
    val wearableTypes: List<HealthDataType> = listOf(
        HealthDataType.STEPS,
        HealthDataType.DISTANCE,
        HealthDataType.ACTIVE_CALORIES,
        HealthDataType.TOTAL_CALORIES,
        HealthDataType.HEART_RATE,
        HealthDataType.RESTING_HEART_RATE,
        HealthDataType.HEART_RATE_VARIABILITY,
        HealthDataType.SLEEP,
        HealthDataType.OXYGEN_SATURATION,
        HealthDataType.EXERCISE,
        HealthDataType.RESPIRATORY_RATE,
    )

    /** Types whose scale values the app writes. */
    val exportTypes: List<HealthDataType> = HealthDataType.entries.filter { it.writePermission != null }

    /** Everything to ask for; the history permission only when the device supports it. */
    fun requested(historyAvailable: Boolean): Set<String> =
        readPermissions + writePermissions + if (historyAvailable) setOf(READ_HISTORY) else emptySet()

    fun readableTypes(granted: Set<String>): Set<HealthDataType> =
        HealthDataType.entries.filter { it.readPermission != null && it.readPermission in granted }.toSet()

    fun writableTypes(granted: Set<String>): Set<HealthDataType> =
        HealthDataType.entries.filter { it.writePermission != null && it.writePermission in granted }.toSet()

    fun canRead(granted: Set<String>, type: HealthDataType): Boolean = type.readPermission?.let { it in granted } == true

    fun canWrite(granted: Set<String>, type: HealthDataType): Boolean = type.writePermission?.let { it in granted } == true

    fun hasAnyWearableRead(granted: Set<String>): Boolean = wearableTypes.any { canRead(granted, it) }

    /** Scale export needs at least the weight write permission; other values are optional. */
    fun canExport(granted: Set<String>): Boolean = canWrite(granted, HealthDataType.WEIGHT)

    fun historyGranted(granted: Set<String>): Boolean = READ_HISTORY in granted

    /** Days to import on a full read. */
    fun importWindowDays(granted: Set<String>): Int =
        if (historyGranted(granted)) HISTORY_WINDOW_DAYS else DEFAULT_WINDOW_DAYS

    fun missing(granted: Set<String>, historyAvailable: Boolean): Set<String> = requested(historyAvailable) - granted

    /** True when every requested permission is granted. */
    fun allGranted(granted: Set<String>, historyAvailable: Boolean): Boolean = missing(granted, historyAvailable).isEmpty()

    /** Wearable types the user has not allowed, for the "faltan permisos" hint. */
    fun missingWearableTypes(granted: Set<String>): List<HealthDataType> = wearableTypes.filterNot { canRead(granted, it) }
}
