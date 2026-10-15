package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.adv
import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.AiLinkBroadcastDriver
import com.enjoyyourtime.globalfitness.scale.drivers.AndUC352Driver
import com.enjoyyourtime.globalfitness.scale.drivers.BodyConnectDriver
import com.enjoyyourtime.globalfitness.scale.drivers.QnBroadcastDriver
import com.enjoyyourtime.globalfitness.scale.drivers.StandardWeightProfileDriver
import com.enjoyyourtime.globalfitness.scale.drivers.StandardWeightProfileProtocol
import com.enjoyyourtime.globalfitness.scale.drivers.WeightGurusA3Driver
import com.enjoyyourtime.globalfitness.scale.drivers.XiaomiS800Driver
import com.enjoyyourtime.globalfitness.scale.drivers.transtekSlot
import com.enjoyyourtime.globalfitness.scale.libs.AesCcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ProtocolFamiliesTest {
    private val ucp = uuid16(0x2A9F)
    private val weight = uuid16(0x2A9D)

    private fun local(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    @Test
    fun sigScaleRegistersANewUserAndIgnoresOtherUsers() {
        val h = ProtocolHarness(StandardWeightProfileDriver()).connect()
        val register = h.io.writes.single { it.characteristic == ucp }.payload
        assertEquals(0x01, Bytes.u8(register, 0))
        val pin = Bytes.fromUnsignedInt16Le(register, 1)
        h.notify(ucp, "20 01 01 03")
        assertEquals(3, h.store.getInt(StandardWeightProfileProtocol.KEY_LEARNED_INDEX, -1))
        assertEquals(pin, h.store.getInt(StandardWeightProfileProtocol.KEY_LEARNED_CONSENT, -1))
        assertArrayEquals(byteArrayOf(0x02, 0x03) + Bytes.toInt16Le(pin), h.io.writes.last().payload)
        h.notify(ucp, "20 02 01")
        assertArrayEquals(hex("C6 07 01 01"), h.io.writes.single { it.characteristic == uuid16(0x2A85) }.payload) // 1990-01-01
        assertArrayEquals(hex("B4 00"), h.io.writes.single { it.characteristic == uuid16(0x2A8E) }.payload) // 180 cm

        h.notify(weight, "06 40 38 EA 07 08 16 08 2B 0B 05") // user 5: someone else
        h.notify(weight, "06 54 33 EA 07 08 16 08 2A 24 03") // user 3: us
        h.io.fireTimers()
        val r = h.readings.single()
        assertEquals(65.7, r.weightKg, 1e-3)
        assertEquals(LocalDateTime.of(2026, 8, 22, 8, 42, 36), local(r.measuredAtMillis))
    }

    @Test
    fun sigScaleUsesTheConfiguredUserAndPin() {
        val store = MapDriverStore(mapOf(ConfigField.KEY_USER_SLOT to "2", ConfigField.KEY_PIN to "1234"))
        val h = ProtocolHarness(StandardWeightProfileDriver(), store = store).connect()
        assertArrayEquals(hex("02 02 D2 04"), h.io.writes.single { it.characteristic == ucp }.payload)
    }

    @Test
    fun andUc352CapturedIndications() {
        val captured = listOf(
            "025433ea070816082a24" to Triple(65.7, 42, 36),
            "024033ea070816082b0b" to Triple(65.6, 43, 11),
            "027c33ea070816082b28" to Triple(65.9, 43, 40),
        )
        for ((payload, expected) in captured) {
            val h = ProtocolHarness(AndUC352Driver()).connect()
            h.notify(weight, payload)
            h.protocol.onDisconnected()
            val r = h.readings.single()
            assertEquals(expected.first, r.weightKg, 1e-3)
            assertEquals(LocalDateTime.of(2026, 8, 22, 8, expected.second, expected.third), local(r.measuredAtMillis))
        }
    }

    @Test
    fun bodyConnectPairsRegistersAndMeasures() {
        val h = ProtocolHarness(BodyConnectDriver()).connect()
        val upload = uuid16(0x8A82)
        val pw = 0x12345678
        h.notify(upload, byteArrayOf(0xA0.toByte()) + Bytes.toInt32Le(pw.toLong()))
        assertEquals(pw.toString(), h.store.getString("bodyconnect_password"))
        assertEquals(0x21, Bytes.u8(h.io.writes.last().payload, 0))
        h.notify(upload, byteArrayOf(0xA1.toByte()) + Bytes.toInt32Le(0x11111111L))
        assertArrayEquals(byteArrayOf(0x20) + Bytes.toInt32Le((0x11111111 xor pw).toLong()), h.io.writes.last().payload)
        for (slot in 1..8) {
            val name = ByteArray(16) { 0x20 }
            if (slot == 1) "Alex".toByteArray().copyInto(name)
            h.notify(upload, byteArrayOf(0x83.toByte(), slot.toByte()) + name)
        }
        val addUser = h.io.writes.single { Bytes.u8(it.payload, 0) == 0x03 }.payload
        assertEquals(2, Bytes.u8(addUser, 1)) // first free slot
        assertEquals("Test", String(addUser.copyOfRange(2, 18)).trim())
        assertEquals(2, h.store.getInt("bodyconnect_slot", -1))

        val ts = 524159516L
        val tsLe = Bytes.toInt32Le(ts)
        h.notify(uuid16(0x8A24), byteArrayOf(0x1F, 0x54, 0x1F, 0x00, 0xFE.toByte()) + tsLe + hex("00000000FFB21300FF011900"))
        h.notify(uuid16(0x8A22), byteArrayOf(0x7F) + tsLe + hex("010000 B0F0 68F2 00F0 ABF1 24F0 0000"))
        val r = h.readings.single()
        assertEquals(80.2, r.weightKg, 1e-3)
        assertEquals(17.6, r.composition!!.fatPercent!!, 1e-3)
        assertEquals(61.6, r.composition!!.waterPercent!!, 1e-3)
        assertEquals(42.7, r.composition!!.musclePercent!!, 1e-3)
        assertEquals(3.6, r.composition!!.boneKg!!, 1e-3)
        assertEquals(BodyConnectDriver.deviceTimeToJava(ts.toInt()), r.measuredAtMillis)
    }

    @Test
    fun transtekSlotChoiceNeverOverwritesAnotherPerson() {
        assertEquals(5, transtekSlot(null, 5, "Sam", 18, mapOf(1 to "Sam"), setOf(2, 3)))
        assertEquals(4, transtekSlot(null, null, "Sam", 18, mapOf(1 to "Alex", 4 to "sam"), setOf(6, 7, 8)))
        assertEquals(3, transtekSlot(null, null, "Sam", 18, mapOf(1 to "Alex"), setOf(7, 3, 5)))
        assertEquals(7, transtekSlot(7, 2, "Sam", 18, emptyMap(), emptySet()))
        assertNull(transtekSlot(null, null, "Sam", 18, mapOf(1 to "Alex"), emptySet()))
    }

    @Test
    fun weightGurusFloatsAndMeasurement() {
        assertEquals(77.25f, WeightGurusA3Driver.float32(byteArrayOf(0x2D, 0x1E, 0x00, 0xFE.toByte()), 0), 1e-3f)
        assertEquals(500f, WeightGurusA3Driver.float32(hex("F4010000"), 0), 1e-3f)
        assertEquals(0f, WeightGurusA3Driver.float32(byteArrayOf(0x1F, 0x2D, 0x1E, 0x00, 0xFE.toByte()), 3))
        assertEquals(21.3f, WeightGurusA3Driver.sfloat(hex("D5F0"), 0), 1e-3f)
        assertEquals(12f, WeightGurusA3Driver.sfloat(hex("0C00"), 0), 1e-3f)
        assertEquals(-1f, WeightGurusA3Driver.sfloat(hex("FF0F"), 0), 1e-3f)
        assertEquals(-2048f, WeightGurusA3Driver.sfloat(hex("0008"), 0), 1e-3f)

        val store = MapDriverStore(mapOf("weightgurus_password" to "12345678", "weightgurus_slot" to "4"))
        val h = ProtocolHarness(WeightGurusA3Driver(), store = store).connect()
        h.notify(uuid16(0x8A82), "A1 11 11 11 11")
        assertArrayEquals(hex("20 03 25 47 69"), h.io.writes.single { Bytes.u8(it.payload, 0) == 0x20 }.payload)
        val profile = h.io.writes.single { Bytes.u8(it.payload, 0) == 0x51 }.payload
        assertArrayEquals(hex("51 17 04 01"), profile.copyOf(4))
        assertEquals(0xD708, Bytes.fromUnsignedInt16Le(profile, 5)) // 1.80 m as SFLOAT

        val ts = Bytes.toInt32Le(524159516L)
        h.notify(uuid16(0x8A24), byteArrayOf(0x15, 0x2D, 0x1E, 0x00, 0xFE.toByte()) + ts + hex("F4010000 10"))
        assertTrue(h.readings.isEmpty()) // waits for the composition frame
        h.notify(uuid16(0x8A22), byteArrayOf(0x6E) + ts + hex("7206 D5F0 26F2 9AF1 23F0"))
        val r = h.readings.single()
        assertEquals(77.25, r.weightKg, 1e-3)
        assertEquals(500, r.impedance)
        assertNull(r.formulaLib)
        val c = r.composition!!
        assertEquals(21.3, c.fatPercent!!, 1e-3)
        assertEquals(55.0, c.waterPercent!!, 1e-3)
        assertEquals(41.0, c.musclePercent!!, 1e-3)
        assertEquals(2.704, c.boneKg!!, 1e-3)
        assertEquals(1650.0, c.bmrKcal!!, 1e-3)
        assertEquals(WeightGurusA3Driver.deviceTimeToJava(524159516), r.measuredAtMillis)
    }

    @Test
    fun aiLinkCompletedBroadcast() {
        val driver = AiLinkBroadcastDriver()
        val env = ScaleTestSupport.env(driver.id, address = "0A:1B:2C:3D:4E:5F")
        val session = driver.newSession(env)
        val a = adv("EL1", listOf(uuid16(0xF0A0)), manufacturer = mapOf(0x0301 to hex("015F4E3D2C1B0A8F10E69CF7CB452CCCFFFF")), address = "0A:1B:2C:3D:4E:5F")
        val r = session.parseAdvertisement(a, env).single()
        assertEquals(70.0, r.weightKg, 1e-3)
        assertEquals(500, r.impedance)
        assertEquals(FormulaLib.STANDARD_IMPEDANCE, r.formulaLib)
        val corrupted = hex("015F4E3D2C1B0A9010E69CF7CB452CCCFFFF")
        assertNull(driver.match(adv("EL1", listOf(uuid16(0xF0A0)), manufacturer = mapOf(0x0301 to corrupted))))
    }

    @Test
    fun qnFt26rBroadcastSettles() {
        val moving = "aabb00005e0053016d000000ffffff1400671b510e032100"
        val settled1 = "aabb00005e00530172000000ffffff1500761b510e032300"
        val settled2 = "aabb00005e0053017b000000ffffff1500761b510e032300"
        val idle = "aabb00005e00530100f09f6affffff15a00000510e03500f"
        assertFalse(QnBroadcastDriver.parseFrame(hex(moving))!!.stable)
        assertTrue(QnBroadcastDriver.parseFrame(hex(settled1))!!.stable)
        assertNull(QnBroadcastDriver.parseFrame(hex(idle)))
        val foreign = hex(settled1).also { it[19] = 0; it[20] = 0; it[21] = 0 }
        assertFalse(QnBroadcastDriver.parseFrame(foreign)!!.stable)

        val driver = QnBroadcastDriver()
        val env = ScaleTestSupport.env(driver.id)
        val session = driver.newSession(env)
        val out = listOf(moving, settled1, settled2, idle).flatMap { session.parseAdvertisement(adv(manufacturer = mapOf(0xFFFF to hex(it))), env) }
        assertEquals(70.30, out.single().weightKg, 1e-2)
    }

    @Test
    fun xiaomiS800EncryptedWeight() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val mac = hex("aba18f47ae04")
        val raw = 8240
        val obj = byteArrayOf(0x16, 0x4E, 0x09, 0x90.toByte(), 0, 0, 0x05, 0x2B, 0, 0, (raw and 0xFF).toByte(), (raw shr 8).toByte())
        val ext = hex("010000")
        val nonce = mac + hex("e2515b") + ext
        val sealed = AesCcm.encrypt(key, nonce, obj, byteArrayOf(0x11), 4)
        val frame = hex("5859e2515b") + mac + sealed.copyOfRange(0, sealed.size - 4) + ext + sealed.copyOfRange(sealed.size - 4, sealed.size)

        val driver = XiaomiS800Driver()
        val messages = mutableListOf<String>()
        val noKey = ScaleTestSupport.env(driver.id, messages = messages)
        val a = adv(serviceData = mapOf(uuid16(0xFE95) to frame))
        assertEquals(MatchConfidence.HIGH, driver.match(a))
        assertTrue(driver.newSession(noKey).parseAdvertisement(a, noKey).isEmpty())
        assertEquals(1, messages.size)

        val env = ScaleTestSupport.env(driver.id, store = MapDriverStore(mapOf(ConfigField.KEY_BINDKEY to Bytes.toHex(key))))
        assertEquals(82.40, driver.newSession(env).parseAdvertisement(a, env).single().weightKg, 1e-2)
        val wrong = ScaleTestSupport.env(driver.id, store = MapDriverStore(mapOf(ConfigField.KEY_BINDKEY to "ff".repeat(16))))
        assertTrue(driver.newSession(wrong).parseAdvertisement(a, wrong).isEmpty())
    }
}
