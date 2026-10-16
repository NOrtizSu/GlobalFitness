package com.enjoyyourtime.globalfitness.wearable.zeppos

import java.math.BigInteger
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NIST B-163 group law and the key exchange Zepp OS devices use. */
class EcdhB163Test {
    private val rnd = Random(20261016)

    private fun privateKey(): ByteArray {
        while (true) {
            val k = ByteArray(EcdhB163.PRIVATE_KEY_SIZE).also { rnd.nextBytes(it) }
            if (EcdhB163.generatePublic(k) != null) return k
        }
    }

    @Test
    fun basePointIsOnTheCurveAndHasTheGroupOrder() {
        assertTrue(EcdhB163.onCurve(EcdhB163.BASE_X.copyOf(), EcdhB163.BASE_Y.copyOf()))
        val x = EcdhB163.BASE_X.copyOf()
        val y = EcdhB163.BASE_Y.copyOf()
        EcdhB163.pointMul(x, y, EcdhB163.BASE_ORDER.copyOf())
        assertTrue("n * G must be the point at infinity", EcdhB163.pointIsZero(x, y))
        // The order is the published B-163 n (0x4 0000000000000000000292FE77E70C12A4234C33).
        val words = EcdhB163.BASE_ORDER
        var n = BigInteger.ZERO
        for (i in words.indices.reversed()) n = n.shiftLeft(32).or(BigInteger.valueOf(words[i].toLong() and 0xffffffffL))
        assertEquals(BigInteger("40000000000000000000292FE77E70C12A4234C33", 16), n)
    }

    @Test
    fun publicKeysAreCurvePoints() {
        repeat(5) {
            val pub = EcdhB163.generatePublic(privateKey())!!
            assertEquals(48, pub.size)
            assertTrue(EcdhB163.onCurve(EcdhB163.toWords(pub, 0), EcdhB163.toWords(pub, 24)))
        }
    }

    @Test
    fun sharedSecretIsSymmetric() {
        repeat(5) {
            val a = privateKey()
            val b = privateKey()
            val sharedA = EcdhB163.generateShared(a, EcdhB163.generatePublic(b)!!)
            val sharedB = EcdhB163.generateShared(b, EcdhB163.generatePublic(a)!!)
            assertNotNull(sharedA)
            assertArrayEquals(sharedA, sharedB)
        }
    }

    @Test
    fun deterministicAndInputUntouched() {
        val k = privateKey()
        val copy = k.copyOf()
        assertArrayEquals(EcdhB163.generatePublic(k), EcdhB163.generatePublic(k))
        assertArrayEquals(copy, k)
    }

    @Test
    fun rejectsWeakKeysAndInvalidPoints() {
        assertNull(EcdhB163.generatePublic(ByteArray(24)))
        assertNull(EcdhB163.generatePublic(ByteArray(24).also { it[0] = 1 }))
        val k = privateKey()
        assertNull(EcdhB163.generateShared(k, ByteArray(48)))
        val bad = EcdhB163.generatePublic(privateKey())!!.also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(EcdhB163.generateShared(k, bad))
        assertNull(EcdhB163.generateShared(k, ByteArray(10)))
        assertFalse(EcdhB163.onCurve(IntArray(6), IntArray(6)))
    }
}
