// Protocol ported from openScale (GPL-3.0), https://github.com/oliexdev/openScale
package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.AdvertisementInfo
import com.enjoyyourtime.globalfitness.scale.Bytes
import com.enjoyyourtime.globalfitness.scale.GattProtocol
import com.enjoyyourtime.globalfitness.scale.GattScaleDriver
import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.scale.ScaleCapabilities
import com.enjoyyourtime.globalfitness.scale.ScaleSample
import com.enjoyyourtime.globalfitness.scale.uuid16
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Medisana BS430 / BS440 / BS444 (service 0x78B2): weight on 8A21, composition on 8A22. The
 * scale clock is either Unix time or seconds since 2010 depending on the model; the epoch is
 * predicted from the name and auto-corrected (and remembered) from the received timestamps.
 */
class MedisanaBs44xDriver : GattScaleDriver() {
    override val id = "medisana_bs44x"
    override val displayName = "Medisana BS430 / BS440 / BS444"
    override val brand = "Medisana"
    override val capabilities = ScaleCapabilities(deviceComposition = true)
    override val keywords = listOf("BS430", "BS440", "BS444", "BS44x")

    override fun match(adv: AdvertisementInfo): MatchConfidence? {
        val n = adv.nameOrEmpty.lowercase(Locale.ROOT)
        val byName = n.startsWith("013197") || n.startsWith("013198") || n.startsWith("0202b6") || n.startsWith("0203b")
        return when {
            byName -> MatchConfidence.HIGH
            adv.serviceUuids.contains(SERVICE) -> MatchConfidence.HIGH
            else -> null
        }
    }

    override fun newProtocol(adv: AdvertisementInfo?): GattProtocol = Protocol(predictEpoch(adv?.nameOrEmpty.orEmpty()))

    enum class EpochMode { UNIX, FROM_2010 }

    internal class Protocol(private val predicted: EpochMode?) : GattProtocol() {
        private val chrWeight = uuid16(0x8A21)
        private val chrFeature = uuid16(0x8A22)
        private val chrCmd = uuid16(0x8A81)
        private val chrCustom5 = uuid16(0x8A82)
        private var epochMode: EpochMode? = null
        private var current: ScaleSample? = null

        override fun onConnected() {
            epochMode = loadEpochMode() ?: predicted ?: EpochMode.UNIX
            setNotifyOn(SERVICE, chrFeature)
            setNotifyOn(SERVICE, chrWeight)
            setNotifyOn(SERVICE, chrCustom5)
            val nowSec = env.wallClock() / 1000
            val ts = if (epochMode == EpochMode.FROM_2010) nowSec - EPOCH_2010 else nowSec
            writeTo(SERVICE, chrCmd, byteArrayOf(0x02) + Bytes.toInt32Le(ts))
            userInfo("Súbete a la báscula")
        }

        override fun onNotification(characteristic: UUID, data: ByteArray) {
            when (characteristic) {
                chrWeight -> parseWeight(data)
                chrFeature -> {
                    parseFeature(data)
                    current?.let { publish(it) }
                    current = null
                }
            }
        }

        private fun parseWeight(d: ByteArray) {
            if (d.size < 9) return
            val m = current ?: ScaleSample().also { current = it }
            m.weightKg = Bytes.fromUnsignedInt16Le(d, 1) / 100.0f
            m.dateTime = mapTimestamp(Bytes.fromUnsignedInt32Le(d, 5)) * 1000
        }

        private fun parseFeature(d: ByteArray) {
            if (d.size < 16) return
            val m = current ?: ScaleSample().also { current = it }
            m.fatPercent = tenth12(d, 8)
            m.waterPercent = tenth12(d, 10)
            m.musclePercent = tenth12(d, 12)
            m.boneKg = tenth12(d, 14)
        }

        private fun tenth12(d: ByteArray, off: Int): Float = (Bytes.fromUnsignedInt16Le(d, off) and 0x0FFF) / 10.0f

        /** Picks the epoch that puts the timestamp within ±90 days of now (persisted when it flips). */
        private fun mapTimestamp(raw: Long): Long {
            val now = env.wallClock() / 1000
            val near = 90L * 24 * 3600
            fun nearNow(x: Long) = abs(x - now) <= near
            val unix = raw
            val from2010 = raw + EPOCH_2010
            return when {
                epochMode == EpochMode.UNIX && !nearNow(unix) && nearNow(from2010) -> {
                    saveEpochMode(EpochMode.FROM_2010)
                    from2010
                }
                epochMode == EpochMode.FROM_2010 && nearNow(unix) && !nearNow(from2010) -> {
                    saveEpochMode(EpochMode.UNIX)
                    unix
                }
                epochMode == EpochMode.FROM_2010 -> from2010
                else -> unix
            }
        }

        private fun loadEpochMode(): EpochMode? = when (settingsGetString(KEY_EPOCH)) {
            "unix" -> EpochMode.UNIX
            "2010" -> EpochMode.FROM_2010
            else -> null
        }

        private fun saveEpochMode(mode: EpochMode) {
            epochMode = mode
            settingsPutString(KEY_EPOCH, if (mode == EpochMode.UNIX) "unix" else "2010")
        }
    }

    companion object {
        val SERVICE: UUID = uuid16(0x78B2)
        const val EPOCH_2010 = 1262304000L
        private const val KEY_EPOCH = "epoch_mode"

        fun predictEpoch(name: String): EpochMode? {
            val n = name.lowercase(Locale.ROOT)
            return when {
                n.startsWith("0203b") -> EpochMode.UNIX
                n.startsWith("013197") || n.startsWith("013198") || n.startsWith("0202b6") -> EpochMode.FROM_2010
                else -> null
            }
        }
    }
}
