package com.enjoyyourtime.globalfitness.scale

import com.enjoyyourtime.globalfitness.scale.ScaleTestSupport.hex
import com.enjoyyourtime.globalfitness.scale.drivers.XiaomiS400Driver
import com.enjoyyourtime.globalfitness.scale.libs.AesCcm
import com.enjoyyourtime.globalfitness.scale.libs.S400Aggregator
import com.enjoyyourtime.globalfitness.scale.libs.S400BodyComposition
import com.enjoyyourtime.globalfitness.scale.libs.S400Decryptor
import com.enjoyyourtime.globalfitness.scale.libs.S400Inputs
import com.enjoyyourtime.globalfitness.scale.libs.S400Packet
import com.enjoyyourtime.globalfitness.scale.libs.S400Reliability
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiS400Test {

    @Test
    fun aesCcmMatchesRfc3610PacketVector1() {
        val key = hex("C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF")
        val nonce = hex("00000003020100A0A1A2A3A4A5")
        val aad = hex("0001020304050607")
        val plain = hex("08090A0B0C0D0E0F101112131415161718191A1B1C1D1E")
        val out = AesCcm.encrypt(key, nonce, plain, aad, tagLength = 8)
        assertEquals("588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0", Bytes.toHex(out))
        assertArrayEquals(plain, AesCcm.decrypt(key, nonce, out, aad, tagLength = 8))
        out[3] = (out[3].toInt() xor 1).toByte()
        assertNull("tampered ciphertext must not verify", AesCcm.decrypt(key, nonce, out, aad, tagLength = 8))
    }

    // Captured payloads from the mi-scale-exporter project (also used by openScale's tests).
    private val mac = "84:46:93:64:A5:E6"
    private val key = "58305740b64e4b425e518aa1f4e51339"

    @Test
    fun decryptsRealAdvertisements() {
        assertEquals(74.2f, S400Decryptor.decrypt(hex("4859d53b2d3314943c58b133638c7457a4000000c3e670dc"), mac, key)!!.weightKg, 0.05f)
        assertEquals(73.2f, S400Decryptor.decrypt(hex("95FE4859D53B3BDE6BC8D05B51C0CDFD9021C9000000925C5039"), mac, key)!!.weightKg, 0.05f)

        val a = S400Decryptor.decrypt(hex("4859d53b0abc078ff2348c844138e930220000009e538599"), "8C:D0:B2:F6:BE:EF", "0728974d657a4b60964c1b1677f35f7c")!!
        assertEquals(69.9f, a.weightKg, 0.05f)
        assertEquals(543.2f, a.impedanceHigh!!, 0.05f)
        assertNull(a.impedanceLow)
        assertEquals(92, a.heartRate)

        val b = S400Decryptor.decrypt(hex("4859d53b0bd6ef0b25db72785e7e2f46d6000000d8642df6"), "8C:D0:B2:F6:BE:EF", "0728974d657a4b60964c1b1677f35f7c")!!
        assertEquals(0f, b.weightKg, 0f)
        assertEquals(497.6f, b.impedanceLow!!, 0.05f)
        assertNull(b.heartRate)
    }

    @Test
    fun rejectsWrongKeyAndBadInput() {
        val data = hex("4859d53b2d3314943c58b133638c7457a4000000c3e670dc")
        assertNull(S400Decryptor.decrypt(data, mac, "00000000000000000000000000000000"))
        assertNull(S400Decryptor.decrypt(data, mac, "58305740b64e4b42"))
        assertNull(S400Decryptor.decrypt(hex("1059d53b06e6a564934684"), mac, key))
        assertNull(S400Decryptor.decrypt(byteArrayOf(), mac, key))
        assertTrue(S400Decryptor.isValidBindKey("ABCDEF0123456789abcdef0123456789"))
    }

    /** Builds an encrypted MiBeacon S400 advertisement exactly like the scale does. */
    private fun encryptAdvert(macAddress: String, bindKey: String, weightRaw: Int, hrRaw: Int, impedanceRaw: Int, counter: Int): ByteArray {
        val header = byteArrayOf(0x48, 0x59, 0xD5.toByte(), 0x3B, counter.toByte())
        val extCounter = byteArrayOf(0x01, 0x00, 0x00)
        val word = (weightRaw and 0x7FF) or ((hrRaw and 0x7F) shl 11) or (impedanceRaw shl 18)
        val plain = ByteArray(12).also {
            it[0] = 0x16
            it[1] = 0x6E
            it[2] = 0x09 // object 0x6E16, 9 bytes
            it[3] = 0x01 // scale profile
            Bytes.toInt32Le(it, 4, word.toLong() and 0xFFFFFFFFL)
        }
        val nonce = Bytes.fromHex(macAddress).reversedArray() + header.copyOfRange(2, 5) + extCounter
        val sealed = AesCcm.encrypt(Bytes.fromHex(bindKey), nonce, plain, byteArrayOf(0x11), 4)
        return header + sealed.copyOfRange(0, 12) + extCounter + sealed.copyOfRange(12, 16)
    }

    @Test
    fun driverDecryptsAggregatesAndComputesComposition() {
        val bindKey = "00112233445566778899aabbccddeeff"
        val driver = XiaomiS400Driver()
        val clock = ScaleTestSupport.Clock()
        val store = MapDriverStore()
        val messages = mutableListOf<ScaleNotice>()
        val env = ScaleTestSupport.env(driver.id, clock, store = store, messages = messages)
        val session = driver.newSession(env)
        fun advert(payload: ByteArray) = ScaleTestSupport.adv(serviceData = mapOf(Bytes.uuid16(0xFE95) to payload))

        val packetA = encryptAdvert(ScaleTestSupport.ADDRESS, bindKey, weightRaw = 755, hrRaw = 22, impedanceRaw = 5100, counter = 7)
        // Recognised by the MiBeacon product id even without a name.
        assertEquals(MatchConfidence.HIGH, driver.match(advert(packetA)))

        // No bind key yet: nothing decoded, one hint for the user.
        assertTrue(session.parseAdvertisement(advert(packetA), env).isEmpty())
        assertEquals(1, messages.size)

        store.putString(ConfigField.KEY_BINDKEY, bindKey)
        assertTrue(session.parseAdvertisement(advert(packetA), env).isEmpty()) // waits for packet B
        val packetB = encryptAdvert(ScaleTestSupport.ADDRESS, bindKey, weightRaw = 0, hrRaw = 0, impedanceRaw = 5600, counter = 8)
        clock.advance(500)
        val readings = session.parseAdvertisement(advert(packetB), env)
        assertEquals(1, readings.size)
        val r = readings.single()
        assertEquals(75.5, r.weightKg, 1e-4)
        assertEquals(510, r.impedance)
        assertEquals(CompositionSource.Formula(com.enjoyyourtime.globalfitness.scale.FormulaLib.S400), r.compositionSource)
        val c = assertNotNullComposition(r)
        assertTrue("fat ${c.fatPercent}", c.fatPercent!! in 8.0..35.0)
        assertTrue(c.waterPercent!! in 40.0..70.0)

        // The scale re-broadcasts the result: not published twice.
        clock.advance(2_000)
        assertTrue(session.parseAdvertisement(advert(packetA), env).isEmpty())
        assertTrue(session.parseAdvertisement(advert(packetB), env).isEmpty())
    }

    private fun assertNotNullComposition(r: ScaleReading) = r.composition.also { assertNotNull(it) }!!

    /** Older firmware sends packet A only: the session's tick finalizes it after the timeout. */
    @Test
    fun driverFinalizesPacketAAloneOnTick() {
        val bindKey = "00112233445566778899aabbccddeeff"
        val driver = XiaomiS400Driver()
        val clock = ScaleTestSupport.Clock()
        val env = ScaleTestSupport.env(driver.id, clock, store = MapDriverStore(mapOf(ConfigField.KEY_BINDKEY to bindKey)))
        val session = driver.newSession(env)
        val packetA = encryptAdvert(ScaleTestSupport.ADDRESS, bindKey, weightRaw = 755, hrRaw = 22, impedanceRaw = 5100, counter = 9)
        assertTrue(session.parseAdvertisement(ScaleTestSupport.adv(serviceData = mapOf(Bytes.uuid16(0xFE95) to packetA)), env).isEmpty())
        assertTrue(session.needsTick)
        clock.advance(5_000)
        assertTrue(session.tick(env).isEmpty())
        clock.advance(5_000)
        val r = session.tick(env).single()
        assertEquals(75.5, r.weightKg, 1e-4)
        assertEquals(510, r.impedance)
        assertNull(r.impedanceLow)
        assertTrue(!session.needsTick)
    }

    @Test
    fun aggregatorFinalizesWithPacketAOnlyAfterTimeout() {
        val agg = S400Aggregator()
        assertEquals(S400Aggregator.Outcome.Pending, agg.ingest("m", S400Packet(70f, 500f, null, 80), 0))
        val out = agg.ingest("m", S400Packet(70f, 500f, null, 80), 10_000)
        assertTrue(out is S400Aggregator.Outcome.Finalized && out.timedOut)
    }

    @Test
    fun bodyCompositionSanityAndValidation() {
        val r = S400BodyComposition.compute(S400Inputs(35, true, 180f, 80f, 480f, 540f))
        assertEquals(S400Reliability.OK, r.reliability)
        assertTrue(r.bfPct!! in 10f..30f)
        assertTrue(r.bmrKcal!! in 1400f..2200f)
        // Out of the validated range (age < 18): weight/BMI only.
        assertEquals(S400Reliability.NOT_AVAILABLE, S400BodyComposition.compute(S400Inputs(12, true, 150f, 40f, 500f, 560f)).reliability)
        // Swapped bands are corrected.
        assertTrue(S400BodyComposition.compute(S400Inputs(35, false, 165f, 60f, 560f, 500f)).labelSwapApplied)
    }
}
