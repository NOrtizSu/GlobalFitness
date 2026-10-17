package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.adv
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.AfuB1Driver
import com.enjoyyourtime.globalfitness.scale.drivers.CustomOpenScaleDriver
import com.enjoyyourtime.globalfitness.scale.drivers.EtekcityESF551Driver
import com.enjoyyourtime.globalfitness.scale.drivers.EufyP2Driver
import com.enjoyyourtime.globalfitness.scale.drivers.HoffenBbs8107Driver
import com.enjoyyourtime.globalfitness.scale.drivers.HuaweiAh100Driver
import com.enjoyyourtime.globalfitness.scale.drivers.KeepS3Driver
import com.enjoyyourtime.globalfitness.scale.drivers.OmronWlcDriver
import com.enjoyyourtime.globalfitness.scale.drivers.OneByoneNewDriver
import com.enjoyyourtime.globalfitness.scale.drivers.PicoocDriver
import com.enjoyyourtime.globalfitness.scale.drivers.QnDriver
import com.enjoyyourtime.globalfitness.scale.drivers.SenssunDriver
import com.enjoyyourtime.globalfitness.scale.drivers.YunmaiDriver
import com.enjoyyourtime.globalfitness.scale.libs.EufyP2Lib
import com.enjoyyourtime.globalfitness.scale.libs.KeepS3Protocol
import com.enjoyyourtime.globalfitness.scale.libs.OmronWlpFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Whole GATT protocol flows driven with a fake link (frames from openScale's captures/tests). */
class DriverProtocolsTest {
    @Test
    fun qnUniversalFamilyRealCapture() {
        val svc = uuid16(0xFFF0)
        val notify = uuid16(0xFFF1)
        val io = FakeGattIo(setOf(svc to notify, svc to uuid16(0xFFF2)))
        val h = ProtocolHarness(QnDriver(), io = io).connect()
        h.notify(notify, byteArrayOf(0x12, 0x12, 0xFF.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x13, 0x07, 0x10, 0x00, 0x01, 0x05, 0x9F.toByte(), 0x32, 0x89.toByte()))
        h.notify(notify, "10 0F FF FE 00 21 57 00 00 00 00 00 00 00 94")
        h.notify(notify, "10 0F FF FE 01 21 57 00 00 00 00 00 00 00 95")
        assertTrue(h.readings.isEmpty())
        h.notify(notify, "10 0F FF FE 02 21 57 10 FE 10 FC 00 AC 00 5C")
        val r = h.readings.single()
        assertEquals(85.35, r.weightKg, 1e-3)
        assertEquals(435, r.impedance)
        assertEquals(FormulaLib.TRISA, r.formulaLib)
        assertTrue(io.writtenHex.any { it.equals("1F05FF1033", ignoreCase = true) })
    }

    @Test
    fun huaweiAh100DecodesTheEncryptedMeasurement() {
        val mac = "5C:CA:D3:08:7B:4F"
        val clock = ScaleTestSupport.Clock(ScaleTestSupport.epochMillis(2026, 5, 19, 8, 13))
        val h = ProtocolHarness(HuaweiAh100Driver(), address = mac, clock = clock).connect()
        val rx = uuid16(0xFAA2)
        val macBytes = hex("5CCAD3087B4F")
        h.notify(rx, "BD0100")
        // Auth token for user 1 is 11 22 33 44 55 10 01.
        assertTrue(h.io.writtenHex.last().uppercase().startsWith("DB0824"))
        h.notify(rx, byteArrayOf(0xBD.toByte(), 0x01, 0x26) + byteArrayOf((1 xor macBytes[0].toInt()).toByte()))
        h.notify(rx, "bc100edef702c9bbf2176c755b6eccdd5b92b0")
        val r = h.readings.single()
        assertEquals(97.0, r.weightKg, 1e-3)
        assertEquals(28.0, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(540, r.impedance)
        assertNull(r.formulaLib) // impedance kept, not interpreted
        assertEquals(LocalDateTime.of(2026, 5, 19, 8, 12, 33), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.measuredAtMillis), ZoneId.systemDefault()))
    }

    @Test
    fun keepS3InitialisationAndFinalRecord() {
        val ctx = ScaleTestSupport.ctx(heightCm = 168f, birthYear = 2001)
        val h = ProtocolHarness(KeepS3Driver(), ctx = ctx).connect()
        val notify = uuid16(0xFF01)
        fun response(op: Int, data: ByteArray = byteArrayOf()) = byteArrayOf(0x02, 0x53, op.toByte(), 0x00, data.size.toByte(), 0x80.toByte()) + data
        assertArrayEquals(hex("01 53 38 00 00"), h.io.writes.single().payload)
        for (op in KeepS3Protocol.INIT_SEQUENCE) {
            h.notify(notify, response(op, if (op == KeepS3Protocol.OP_READ_TIME) hex("6A2F0000") else byteArrayOf()))
        }
        val requests = h.io.writes.map { Bytes.u8(it.payload, 2) }
        assertEquals(KeepS3Protocol.INIT_SEQUENCE, requests)
        assertArrayEquals(hex("01 53 01 00 04 6A 2F 00 00"), h.io.writes[2].payload) // clock echoed back
        val token = h.store.getString("keep_s3_token")!!
        assertTrue(KeepS3Protocol.validateToken(token))

        h.notify(notify, "03 53 57 00 08 09 42 7C 00 00 01 2D 00")
        val record = ByteArray(66).also {
            hex("03 53 58 00 3D").copyInto(it)
            KeepS3Protocol.repeatedTokenBytes(token).copyInto(it, 5)
            hex("42 7C 5E 25 5A 26 27 DA FF B4 FF B4 6B").copyInto(it, 53)
        }
        h.notify(notify, record)
        h.notify(notify, record)
        val r = h.readings.single()
        assertEquals(85.10, r.weightKg, 1e-4)
        assertEquals(478, r.impedance)
        assertEquals(CompositionSource.Formula(FormulaLib.KEEP_S3), r.compositionSource)
        assertEquals(29.5, r.composition!!.fatPercent!!, 1e-4)
        assertEquals(50.2, r.composition!!.waterPercent!!, 1e-4)
        assertEquals(1791.0, r.composition!!.bmrKcal!!, 1e-4)
        assertEquals("${h.clock.now / 1000}:17020:301", h.store.getString("keep_s3_previous"))
        assertEquals(2, h.io.writtenHex.count { it.equals("04535800" + "0080", ignoreCase = true) })
        h.io.fireTimers()
        assertTrue(h.io.disconnected)
    }

    @Test
    fun eufyP2AuthenticatesBeforeReadingWeight() {
        val mac = "CF:E6:03:1D:09:F7"
        val h = ProtocolHarness(EufyP2Driver(), address = mac).connect()
        val c0 = h.io.writes.filter { Bytes.u8(it.payload, 0) == 0xC0 }
        assertEquals(2, c0.size)
        val weight = ByteArray(16).also {
            it[0] = 0xCF.toByte()
            it[6] = (8345 and 0xFF).toByte()
            it[7] = (8345 shr 8).toByte()
            it[8] = (543 and 0xFF).toByte()
            it[9] = (543 shr 8).toByte()
        }
        h.notify(uuid16(0xFFF2), weight)
        assertTrue("no weight before authentication", h.readings.isEmpty())
        val c1 = EufyP2Lib.buildSubContract(EufyP2Lib.encryptToHex("DEVICEUUID12345", EufyP2Lib.keyFromMac(mac)), 0xC1)
        c1.forEach { h.notify(uuid16(0xFFF4), it) }
        assertTrue(h.io.writes.any { Bytes.u8(it.payload, 0) == 0xC2 })
        h.notify(uuid16(0xFFF4), "c3 01 00 01 00 c3")
        h.notify(uuid16(0xFFF2), weight)
        val r = h.readings.single()
        assertEquals(83.45, r.weightKg, 1e-3)
        assertEquals(543, r.impedance)
        assertEquals(GattPacing.CONSERVATIVE, EufyP2Driver().pacing)
    }

    @Test
    fun omronReadsTheConfiguredSlotAfterPairing() {
        val record = hex(
            "00 00 3a d0 5f 02 4e 18 3b 6d 22 33 89 cc 00 00" +
                "00 00 00 00 00 00 00 00 00 00 5a 80 00 00 00 00" +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
        )
        val slot = record + ByteArray(48 * 29) { 0xFF.toByte() }
        val h = ProtocolHarness(OmronWlcDriver(), adv("HBF-702T")).connect()
        fun respond(type: Int, address: Int, data: ByteArray) {
            val f = byteArrayOf((8 + data.size).toByte(), (type shr 8).toByte(), type.toByte(), (address shr 8).toByte(), address.toByte(), data.size.toByte()) + data + byteArrayOf(0, 0)
            f[f.lastIndex] = OmronWlpFrame.bcc(f, f.size - 1)
            OmronWlpFrame.toChannelChunks(f).forEachIndexed { i, chunk -> h.notify(OmronWlcDriver.CHR_RX[i], chunk) }
        }
        assertArrayEquals(OmronWlpFrame.enterKeyProgramming(), h.io.writes.last().payload)
        h.notify(OmronWlcDriver.CHR_UNLOCK, "8200")
        val key = h.io.writes.last().payload.copyOfRange(1, 17)
        h.notify(OmronWlcDriver.CHR_UNLOCK, "8000")
        assertEquals(Bytes.toHex(key).lowercase(), h.store.getString("omron_pairing_key")!!.lowercase())
        respond(OmronWlpFrame.TYPE_START, 0, byteArrayOf())
        var cursor = 0
        while (cursor < slot.size) {
            respond(OmronWlpFrame.TYPE_READ, 0x02C0 + cursor, slot.copyOfRange(cursor, cursor + 32))
            cursor += 32
        }
        val r = h.readings.single()
        assertEquals(72.40, r.weightKg, 1e-3)
        assertEquals(23.5, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(1520.0, r.composition!!.bmrKcal!!, 1e-3)
        assertEquals(34.0, r.composition!!.metabolicAge!!, 1e-3)
        respond(0x8F00, 0, byteArrayOf())
        assertTrue(h.io.disconnected)
        assertTrue(h.store.getString("omron_last_record_0") != null)
    }

    @Test
    fun picoocPublishesDeviceCompositionOnceSettled() {
        val h = ProtocolHarness(PicoocDriver()).connect()
        val notify = uuid16(0xFFF1)
        h.notify(notify, "3A")
        assertEquals("F10A3A", Bytes.toHex(h.io.writes.last().payload).take(6).uppercase())
        val composition = ByteArray(20).also {
            it[0] = 0x32
            hex("0640 00E1 0226 0000 0040 0000 09 21 0000 0208").copyInto(it, 2)
        }
        h.notify(notify, composition)
        assertTrue(h.readings.isEmpty())
        h.io.fireTimers()
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertEquals(520, r.impedance)
        assertEquals(FormulaLib.STANDARD_IMPEDANCE, r.formulaLib)
        assertEquals(CompositionSource.Device, r.compositionSource)
        assertEquals(22.5, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(3.2, r.composition!!.boneKg!!, 1e-3)
        assertEquals(33.0, r.composition!!.metabolicAge!!, 1e-3)
        assertTrue(h.io.writes.any { Bytes.u8(it.payload, 0) == 0x31 }) // user profile
    }

    @Test
    fun senssunNeedsAllFourValueGroups() {
        val h = ProtocolHarness(SenssunDriver()).connect()
        val notify = uuid16(0xFFF1)
        h.notify(notify, "FF A5 03 20 00 00 AA")
        assertTrue(h.io.writes.any { Bytes.u8(it.payload, 1) == 0x10 }) // user sent once stable
        h.notify(notify, "FF A5 00 E1 02 26 B0")
        h.notify(notify, "FF A5 01 9A 20 00 C0")
        assertTrue(h.readings.isEmpty())
        h.notify(notify, "FF A5 07 D0 00 00 D0")
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertEquals(22.5, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(41.0, r.composition!!.musclePercent!!, 1e-3)
        assertEquals(3.2, r.composition!!.boneKg!!, 1e-3)
        assertTrue(h.io.disconnected)
    }

    @Test
    fun hoffenHandshakeAndFinalFrame() {
        val h = ProtocolHarness(HoffenBbs8107Driver()).connect()
        val chr = uuid16(0xFFB2)
        assertEquals("FA850400", Bytes.toHex(h.io.writes.last().payload).take(8).uppercase())
        h.notify(chr, HoffenBbs8107Driver.frame(0x03, byteArrayOf()))
        assertArrayEquals(HoffenBbs8107Driver.frame(0x83, byteArrayOf(0x01, 0x00)), h.io.writes.last().payload)
        h.notify(chr, HoffenBbs8107Driver.frame(0x03, byteArrayOf()))
        val payload = hex("20 03 00 E1 00 26 02 9A 01 00 00 20 00 00 5A 00")
        h.notify(chr, HoffenBbs8107Driver.frame(0x02, payload))
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertEquals(22.5, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(55.0, r.composition!!.waterPercent!!, 1e-3)
        assertEquals(41.0, r.composition!!.musclePercent!!, 1e-3)
        assertEquals(3.2, r.composition!!.boneKg!!, 1e-3)
        assertEquals(9.0, r.composition!!.visceralFat!!, 1e-3)
        h.notify(chr, HoffenBbs8107Driver.frame(0x03, byteArrayOf()))
        assertTrue(h.io.disconnected)
    }

    @Test
    fun etekcityEsf551FinalFrame() {
        val frame = hex("A5 02 00 10 00 00 01 61 A1 00 80 38 01 F4 01 00 00 00 00 00 01 00")
        val parsed = EtekcityESF551Driver.parse(frame)!!
        assertEquals(80.0f, parsed.weightKg, 1e-3f)
        assertEquals(500, parsed.impedance)
        assertTrue(parsed.final)
        val h = ProtocolHarness(EtekcityESF551Driver()).connect()
        h.notify(uuid16(0xFFF1), frame.copyOf().also { it[20] = 0 })
        assertTrue(h.readings.isEmpty())
        h.notify(uuid16(0xFFF1), frame)
        assertEquals(500, h.readings.single().impedance)
        assertTrue(h.io.disconnected)
    }

    @Test
    fun customOpenScaleRecords() {
        val values = listOf(1, 26, 10, 15, 8, 30, 80, 22, 55, 40)
        val checksum = values.fold(0) { a, v -> a xor v }
        val h = ProtocolHarness(CustomOpenScaleDriver()).connect()
        val chr = uuid16(0xFFE1)
        val line = "\$D\$1,26,10,15,8,30,80.5,22.1,55.3,40.2,$checksum\n\$F\$\n"
        line.toByteArray().toList().chunked(20).forEach { h.notify(chr, it.toByteArray()) }
        val r = h.readings.single()
        assertEquals(80.5, r.weightKg, 1e-3)
        assertEquals(22.1, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(LocalDateTime.of(2026, 10, 15, 8, 30), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.measuredAtMillis), ZoneId.systemDefault()))
        assertTrue(h.io.writtenHex.last() == "39")
        assertTrue(h.io.disconnected)
        assertNull(CustomOpenScaleDriver.parseRecord("1,26,10,15,8,30,80.5,22.1,55.3,40.2,0"))
    }

    @Test
    fun afuStoredRecordIsPublishedAndAcknowledged() {
        val h = ProtocolHarness(AfuB1Driver()).connect()
        h.notify(AfuB1Driver.WRITE_CHARACTERISTIC, "af450046a4826a7aee0880000127020000005404")
        val r = h.readings.single()
        assertEquals(61.05, r.weightKg, 1e-3)
        assertEquals(551, r.impedance)
        assertEquals(FormulaLib.AFU_B1, r.formulaLib)
        assertEquals(1786946630000L, r.measuredAtMillis)
        assertTrue(h.io.writtenHex.any { it.uppercase().startsWith("AF451254") })
    }

    @Test
    fun oneByoneNewWeightThenImpedance() {
        val h = ProtocolHarness(OneByoneNewDriver(), ctx = ScaleTestSupport.ctx().copy(lastImpedance = 480)).connect()
        val setup = h.io.writes.first().payload
        assertEquals(0xD7, Bytes.u8(setup, 18))
        assertEquals(480, Bytes.u8(setup, 13) or (Bytes.u8(setup, 14) shl 8))
        val notify = uuid16(0xFFB2)
        h.notify(notify, ByteArray(20).also { hex("AB 2A 80 01 38 80").copyInto(it) })
        assertTrue(h.readings.isEmpty())
        h.notify(notify, ByteArray(20).also { hex("AB 2A 01 00 01 F4").copyInto(it) })
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertEquals(500, r.impedance)
        assertEquals(FormulaLib.ONE_BYONE_NEW, r.formulaLib)
    }

    @Test
    fun yunmaiMiniFinalFrameForTheAppUser() {
        val h = ProtocolHarness(YunmaiDriver(isMini = true)).connect()
        val notify = uuid16(0xFFE4)
        val ts = h.clock.now / 1000 - 60
        fun frame(uid: Int) = ByteArray(19).also {
            it[1] = 0x14
            it[3] = 0x02
            Bytes.toInt32Be(it, 5, ts)
            Bytes.toInt32Be(it, 9, uid.toLong())
            Bytes.toInt16Be(it, 13, 8000)
            Bytes.toInt16Be(it, 15, 500)
        }
        h.notify(notify, frame(uid = 7))
        assertTrue(h.readings.isEmpty())
        h.notify(notify, frame(uid = 1))
        h.notify(notify, frame(uid = 1))
        val r = h.readings.single()
        assertEquals(80.0, r.weightKg, 1e-3)
        assertEquals(500, r.impedance)
        assertEquals(FormulaLib.YUNMAI, r.formulaLib)
        assertEquals(ts * 1000L, r.measuredAtMillis)
    }
}
