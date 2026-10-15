// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.libs

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

/** Record layouts of the Omron WLP body-composition monitors (EEPROM records per user slot). */
object OmronBodyCompositionLib {
    class Profile(
        val recordSize: Int,
        val slotBaseAddresses: IntArray,
        val recordsPerSlot: Int,
        val visceralFatStep: Float,
        val hasBodyAge: Boolean,
    ) {
        val userSlotCount: Int get() = slotBaseAddresses.size

        fun recordAddress(slot: Int, index: Int): Int = slotBaseAddresses[slot] + index * recordSize
    }

    val PROFILE_HBF_702T = Profile(48, intArrayOf(0x02C0, 0x0890, 0x0E60, 0x1430), 30, 0.5f, true)
    val PROFILE_HBF_32 = Profile(32, intArrayOf(0x02C0, 0x06A0, 0x0A80, 0x0E60), 30, 1.0f, true)
    val PROFILE_HBF_32_NO_BODY_AGE = Profile(32, intArrayOf(0x02C0, 0x06A0, 0x0A80, 0x0E60), 30, 1.0f, false)

    private class Field(val offset: Int, val size: Int, val startBit: Int, val bitSize: Int)

    private val WEIGHT = Field(26, 2, 4, 12)
    private val BODY_FAT = Field(2, 2, 6, 10)
    private val VISCERAL_FAT = Field(2, 2, 0, 6)
    private val BMR = Field(4, 2, 4, 12)
    private val SKELETAL_MUSCLE = Field(6, 2, 6, 10)
    private val BODY_AGE = Field(10, 1, 0, 7)
    private val YEAR = Field(7, 1, 0, 6)
    private val MINUTE = Field(9, 1, 0, 6)
    private val MONTH = Field(11, 1, 0, 4)
    private val DAY = Field(12, 1, 3, 5)
    private val HOUR = Field(12, 2, 6, 5)
    private val SECOND = Field(13, 1, 0, 6)

    data class Record(
        val timestampMillis: Long,
        val weightKg: Float,
        val bodyFatPercent: Float?,
        val skeletalMusclePercent: Float?,
        val bmrKcal: Int?,
        val visceralFatLevel: Float?,
        val bodyAgeYears: Int?,
    )

    private fun read(record: ByteArray, f: Field): Int {
        var value = 0L
        for (i in 0 until f.size) value = (value shl 8) or (record[f.offset + i].toLong() and 0xFF)
        return ((value ushr f.startBit) and ((1L shl f.bitSize) - 1)).toInt()
    }

    private fun tenths(raw: Int): Float? = raw.takeIf { it != 0 }?.let { it / 10.0f }

    fun decodeRecord(record: ByteArray, profile: Profile, zone: ZoneId = ZoneId.systemDefault()): Record? {
        if (record.size < profile.recordSize) return null
        val head = record.copyOf(profile.recordSize)
        if (head.all { it == 0xFF.toByte() } || head.all { it == 0.toByte() }) return null
        val rawWeight = read(record, WEIGHT)
        if (rawWeight == 0) return null
        val month = read(record, MONTH)
        val day = read(record, DAY)
        val hour = read(record, HOUR)
        val minute = read(record, MINUTE)
        val second = read(record, SECOND)
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val ts = try {
            LocalDateTime.of(2000 + read(record, YEAR), month, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            return null
        }
        return Record(
            timestampMillis = ts,
            weightKg = rawWeight * 0.05f,
            bodyFatPercent = tenths(read(record, BODY_FAT)),
            skeletalMusclePercent = tenths(read(record, SKELETAL_MUSCLE)),
            bmrKcal = read(record, BMR).takeIf { it != 0 },
            visceralFatLevel = read(record, VISCERAL_FAT).takeIf { it != 0 }?.let { it * profile.visceralFatStep },
            bodyAgeYears = if (profile.hasBodyAge) read(record, BODY_AGE).takeIf { it != 0 } else null,
        )
    }
}

/** Omron WLP transport: unlock-channel key commands and BCC-sealed commands split over four channels. */
object OmronWlpFrame {
    const val MAX_READ_BLOCK = 0x20
    const val CHANNEL_WIDTH = 16
    const val KEY_SIZE = 16
    const val TYPE_START = 0x8000
    const val TYPE_READ = 0x8100
    const val ACK_KEY_WRITTEN = 0x8000
    const val ACK_UNLOCKED = 0x8100
    const val ACK_KEY_PROGRAMMING = 0x8200

    fun bcc(bytes: ByteArray, length: Int = bytes.size): Byte {
        var acc = 0
        for (i in 0 until length) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc.toByte()
    }

    private fun sealed(b: ByteArray): ByteArray = b + bcc(b)

    fun startTransmission(): ByteArray = sealed(byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00))

    fun endTransmission(): ByteArray = sealed(byteArrayOf(0x08, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00))

    fun readEeprom(address: Int, length: Int): ByteArray {
        require(length in 1..MAX_READ_BLOCK) { "read block out of range: $length" }
        require(address in 0..0x3FFF) { "address out of range: $address" }
        return sealed(byteArrayOf(0x08, 0x01, 0x00, (address shr 8).toByte(), address.toByte(), length.toByte(), 0x00))
    }

    fun unlock(key: ByteArray): ByteArray = byteArrayOf(0x01) + key.also { require(it.size == KEY_SIZE) }

    fun enterKeyProgramming(): ByteArray = ByteArray(KEY_SIZE + 1).also { it[0] = 0x02 }

    fun writeKey(key: ByteArray): ByteArray = byteArrayOf(0x00) + key.also { require(it.size == KEY_SIZE) }

    fun toChannelChunks(command: ByteArray): List<ByteArray> = command.toList().chunked(CHANNEL_WIDTH) { it.toByteArray() }

    fun channelsForFrame(frameLength: Int): Int = (frameLength + CHANNEL_WIDTH - 1) / CHANNEL_WIDTH

    class Response(val type: Int, val address: Int, val data: ByteArray, val result: Int)

    fun parseResponse(frame: ByteArray): Response? {
        if (frame.size < 8) return null
        val declared = frame[0].toInt() and 0xFF
        if (declared < 8 || declared > frame.size) return null
        val exact = frame.copyOf(declared)
        if (bcc(exact).toInt() != 0) return null
        val type = ((exact[1].toInt() and 0xFF) shl 8) or (exact[2].toInt() and 0xFF)
        val address = ((exact[3].toInt() and 0xFF) shl 8) or (exact[4].toInt() and 0xFF)
        val payload = exact[5].toInt() and 0xFF
        val data = if (payload > declared - 8) ByteArray(payload) { 0xFF.toByte() } else exact.copyOfRange(6, 6 + payload)
        return Response(type, address, data, exact[declared - 2].toInt() and 0xFF)
    }
}
