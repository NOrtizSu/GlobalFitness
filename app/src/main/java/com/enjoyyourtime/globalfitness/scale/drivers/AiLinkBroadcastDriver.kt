// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.DriverEnv
import com.enjoyyourtime.globalfitness.scale.DriverNameNote
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.PassiveScaleDriver
import com.enjoyyourtime.globalfitness.scale.PassiveSession
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.SimplePassiveSession
import java.util.Locale

/**
 * AiLink / eLink broadcast body-fat scales (vendor app "AiLink", names such as "EL1"):
 * non-connectable advertisements with service 0xF0A0 and a TEA-encrypted manufacturer record.
 * Only the completed frame (status 0xFF) is recorded; composition from its impedance with the
 * standard impedance formulas.
 */
class AiLinkBroadcastDriver : PassiveScaleDriver() {
    override val id = "ailink_broadcast"
    override val displayName = "AiLink / eLink"
    override val nameNote = DriverNameNote.BODY_FAT_BROADCAST
    override val brand = "AiLink / Aicare"
    override val capabilities = ScaleCapabilities(impedance = true)
    override val formulaLib = FormulaLib.STANDARD_IMPEDANCE
    override val keywords = listOf("EL1", "eLink", "Pingwang", "Aicare", "F0A0")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        if (!adv.serviceUuids.contains(SERVICE)) return null
        return if (firstFrame(adv) != null) MatchConfidence.HIGH else null
    }

    override fun newSession(env: DriverEnv): PassiveSession = object : SimplePassiveSession(FormulaLib.STANDARD_IMPEDANCE) {
        override fun parse(adv: AdvertisementInfo, env: DriverEnv): ScaleSample? {
            val frame = firstFrame(adv) ?: return null
            if (!frame.isComplete) {
                frame.usableWeightKg?.let { liveWeight(it) }
                return null
            }
            val kg = frame.usableWeightKg ?: return null
            if (kg < 1f || kg > 300f) return null
            return ScaleSample().apply {
                weightKg = kg
                // 0 = the scale's impedance pass failed: weight only.
                if (frame.impedance > 0) impedance = frame.impedance.toFloat()
            }
        }
    }

    companion object {
        val SERVICE = Bytes.uuid16(0xF0A0)

        fun firstFrame(adv: AdvertisementInfo): AiLinkLib.Broadcast? =
            adv.manufacturerData.entries.firstNotNullOfOrNull { (id, data) -> AiLinkLib.parse(id, data) }
    }
}

/**
 * Decoder for AiLink broadcast frames. The company id is really `VID << 8 | CID`; the stripped
 * record is `[PID][MAC×6 LE][checksum][10-byte payload]` where the first 8 payload bytes are
 * TEA-encrypted with a key derived from the device ids. Decrypted payload:
 * `[seq][status][flags][weight BE (bit15 = negative)][impedance BE][algorithm]`.
 */
object AiLinkLib {
    const val STATUS_COMPLETE = 0xFF
    const val UNIT_KG = 0
    const val UNIT_JIN = 1
    const val UNIT_LB = 6
    private val KNOWN_STATUSES = setOf(0x00, 0x01, 0x02, 0x03, 0x04, STATUS_COMPLETE)
    private const val RECORD_LENGTH = 18
    private const val CHECKSUM_INDEX = 7
    private const val PAYLOAD_OFFSET = 8
    private const val PAYLOAD_LENGTH = 10
    private val DELTA = 0x9E3779B9.toInt()
    private val SUM_INITIAL = 0xC6EF3720.toInt()

    data class Broadcast(
        val macAddress: String,
        val sequence: Int,
        val status: Int,
        val weightUnit: Int,
        val decimals: Int,
        val isNegative: Boolean,
        val rawWeight: Int,
        val weightKg: Float?,
        val impedance: Int,
        val algorithm: Int,
    ) {
        val isComplete: Boolean get() = status == STATUS_COMPLETE
        val usableWeightKg: Float? get() = weightKg?.takeIf { !isNegative && rawWeight != 0 }
    }

    fun parse(companyId: Int, data: ByteArray): Broadcast? {
        if (data.size < RECORD_LENGTH) return null
        val cid = companyId and 0xFF
        val vid = (companyId shr 8) and 0xFF
        val pid = Bytes.u8(data, 0)
        val encrypted = data.copyOfRange(PAYLOAD_OFFSET, PAYLOAD_OFFSET + PAYLOAD_LENGTH)
        if (Bytes.sum8(encrypted) != Bytes.u8(data, CHECKSUM_INDEX)) return null
        val p = decryptBroadcast(encrypted, cid, vid, pid)
        val status = Bytes.u8(p, 1)
        if (status !in KNOWN_STATUSES) return null
        val flags = Bytes.u8(p, 2)
        val unit = (flags shr 3) and 0x07
        val decimals = (flags shr 1) and 0x03
        val raw = ((p[3].toInt() and 0x7F) shl 8) or Bytes.u8(p, 4)
        return Broadcast(
            macAddress = (1..6).reversed().joinToString(":") { String.format(Locale.ROOT, "%02X", Bytes.u8(data, it)) },
            sequence = Bytes.u8(p, 0),
            status = status,
            weightUnit = unit,
            decimals = decimals,
            isNegative = p[3].toInt() and 0x80 != 0,
            rawWeight = raw,
            weightKg = when (unit) {
                UNIT_KG -> applyDecimals(raw, decimals)
                UNIT_JIN -> applyDecimals(raw, decimals) / 2f
                UNIT_LB -> applyDecimals(raw, decimals) * 0.45359237f
                else -> null
            },
            impedance = Bytes.fromUnsignedInt16Be(p, 5),
            algorithm = Bytes.u8(p, 7),
        )
    }

    fun broadcastKey(cid: Int, vid: Int, pid: Int): IntArray = intArrayOf(
        fold(pid) + 0x41493000,
        fold(vid) + 0x4C327900,
        fold(cid) + 0x31783100,
        0x306C6531,
    )

    /** TEA (32 rounds) over the first 8 bytes only; trailing bytes pass through. */
    fun decryptBroadcast(payload: ByteArray, cid: Int, vid: Int, pid: Int): ByteArray {
        val out = payload.copyOf()
        if (out.size < 8) return out
        val k = broadcastKey(cid, vid, pid)
        var v0 = Bytes.fromSignedInt32Le(out, 0)
        var v1 = Bytes.fromSignedInt32Le(out, 4)
        var sum = SUM_INITIAL
        repeat(32) {
            v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
            v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
            sum -= DELTA
        }
        Bytes.toInt32Le(out, 0, v0.toLong())
        Bytes.toInt32Le(out, 4, v1.toLong())
        return out
    }

    /** Inverse of [decryptBroadcast] (used to build test vectors). */
    fun encryptBroadcast(payload: ByteArray, cid: Int, vid: Int, pid: Int): ByteArray {
        val out = payload.copyOf()
        if (out.size < 8) return out
        val k = broadcastKey(cid, vid, pid)
        var v0 = Bytes.fromSignedInt32Le(out, 0)
        var v1 = Bytes.fromSignedInt32Le(out, 4)
        var sum = 0
        repeat(32) {
            sum += DELTA
            v0 += ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
            v1 += ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
        }
        Bytes.toInt32Le(out, 0, v0.toLong())
        Bytes.toInt32Le(out, 4, v1.toLong())
        return out
    }

    private fun fold(id: Int) = if (id >= 0x10000) id - 0xFFFF else id

    private fun applyDecimals(raw: Int, decimals: Int): Float = when (decimals) {
        1 -> raw / 10f
        2 -> raw / 100f
        3 -> raw / 1000f
        else -> raw.toFloat()
    }
}
