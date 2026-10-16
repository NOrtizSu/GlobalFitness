// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
// (util/ECDH_B163.java, itself a port of kokke/tiny-ECDH-c, public domain / Unlicense).
package com.enjoyyourtime.globalfitness.wearable.zeppos

/**
 * Elliptic curve Diffie-Hellman on NIST B-163 (sect163r2, GF(2^163), a = 1), exactly as Zepp OS
 * devices expect it: keys are little-endian 32-bit word vectors; a public key / shared point is
 * X (24 bytes) followed by Y (24 bytes). Kept structurally identical to the original to avoid
 * subtle bugs.
 */
object EcdhB163 {
    private const val CURVE_DEGREE = 163
    const val PRIVATE_KEY_SIZE = 24
    const val PUBLIC_KEY_SIZE = 2 * PRIVATE_KEY_SIZE
    private const val BITVEC_MARGIN = 3
    private const val BITVEC_NBITS = CURVE_DEGREE + BITVEC_MARGIN
    private const val NWORDS = (BITVEC_NBITS + 31) / 32
    private const val NBYTES = 4 * NWORDS

    private val POLYNOMIAL = words(0x000000c9, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000008)
    private val COEFF_B = words(0x4a3205fd, 0x512f7874, 0x1481eb10, 0xb8c953ca, 0x0a601907, 0x00000002)
    internal val BASE_X = words(0xe8343e36, 0xd4994637, 0xa0991168, 0x86a2d57e, 0xf0eba162, 0x00000003)
    internal val BASE_Y = words(0x797324f1, 0xb11c5c0c, 0xa2cdd545, 0x71a0094f, 0xd51fbc6c, 0x00000000)
    internal val BASE_ORDER = words(0xa4234c33, 0x77e70c12, 0x000292fe, 0x00000000, 0x00000000, 0x00000004)

    private fun words(vararg w: Long): IntArray = IntArray(w.size) { w[it].toInt() }

    // ---- bit vectors ----

    private fun getBit(x: IntArray, idx: Int): Int = (x[idx / 32] ushr (idx and 31)) and 1

    private fun clrBit(x: IntArray, idx: Int) {
        x[idx / 32] = x[idx / 32] and (1 shl (idx and 31)).inv()
    }

    private fun copy(x: IntArray, y: IntArray) = System.arraycopy(y, 0, x, 0, NWORDS)

    private fun swap(x: IntArray, y: IntArray) {
        val tmp = IntArray(NWORDS)
        copy(tmp, x)
        copy(x, y)
        copy(y, tmp)
    }

    private fun equal(x: IntArray, y: IntArray): Boolean {
        for (i in 0 until NWORDS) if (x[i] != y[i]) return false
        return true
    }

    private fun setZero(x: IntArray) = x.fill(0, 0, NWORDS)

    private fun isZero(x: IntArray): Boolean {
        for (i in 0 until NWORDS) if (x[i] != 0) return false
        return true
    }

    /** Number of the highest one bit + 1. */
    internal fun degree(x: IntArray): Int {
        var i = NWORDS * 32
        var y = NWORDS
        while (i > 0 && x[--y] == 0) i -= 32
        if (i != 0) {
            var mask = 1 shl 31
            while (x[y] and mask == 0) {
                mask = mask ushr 1
                i -= 1
            }
        }
        return i
    }

    /** x := y << nbits (x and y may be the same array only when nbits < 32). */
    private fun lshift(x: IntArray, y: IntArray, shift: Int) {
        val nwords = shift / 32
        var i = 0
        while (i < nwords) {
            x[i] = 0
            i++
        }
        var j = 0
        while (i < NWORDS) {
            x[i] = y[j]
            i++
            j++
        }
        val nbits = shift and 31
        if (nbits != 0) {
            for (k in NWORDS - 1 downTo 1) x[k] = (x[k] shl nbits) or (x[k - 1] ushr (32 - nbits))
            x[0] = x[0] shl nbits
        }
    }

    // ---- GF(2^163) ----

    private fun setOne(x: IntArray) {
        x[0] = 1
        for (i in 1 until NWORDS) x[i] = 0
    }

    private fun isOne(x: IntArray): Boolean {
        if (x[0] != 1) return false
        for (i in 1 until NWORDS) if (x[i] != 0) return false
        return true
    }

    private fun add(z: IntArray, x: IntArray, y: IntArray) {
        for (i in 0 until NWORDS) z[i] = x[i] xor y[i]
    }

    private fun inc(x: IntArray) {
        x[0] = x[0] xor 1
    }

    /** z := x * y (z must not be y). */
    private fun mul(z: IntArray, x: IntArray, y: IntArray) {
        require(z !== y)
        val tmp = IntArray(NWORDS)
        copy(tmp, x)
        if (getBit(y, 0) != 0) copy(z, x) else setZero(z)
        for (i in 1 until CURVE_DEGREE) {
            lshift(tmp, tmp, 1)
            if (getBit(tmp, CURVE_DEGREE) != 0) add(tmp, tmp, POLYNOMIAL)
            if (getBit(y, i) != 0) add(z, z, tmp)
        }
    }

    /** z := 1 / x. */
    private fun inv(z: IntArray, x: IntArray) {
        val u = IntArray(NWORDS)
        val v = IntArray(NWORDS)
        val g = IntArray(NWORDS)
        val h = IntArray(NWORDS)
        copy(u, x)
        copy(v, POLYNOMIAL)
        setZero(g)
        setOne(z)
        while (!isOne(u)) {
            var i = degree(u) - degree(v)
            if (i < 0) {
                swap(u, v)
                swap(g, z)
                i = -i
            }
            lshift(h, v, i)
            add(u, u, h)
            lshift(h, g, i)
            add(z, z, h)
        }
    }

    // ---- points ----

    private fun pointCopy(x1: IntArray, y1: IntArray, x2: IntArray, y2: IntArray) {
        copy(x1, x2)
        copy(y1, y2)
    }

    private fun pointSetZero(x: IntArray, y: IntArray) {
        setZero(x)
        setZero(y)
    }

    internal fun pointIsZero(x: IntArray, y: IntArray): Boolean = isZero(x) && isZero(y)

    private fun pointDouble(x: IntArray, y: IntArray) {
        if (isZero(x)) {
            setZero(y)
        } else {
            val l = IntArray(NWORDS)
            inv(l, x)
            mul(l, l, y)
            add(l, l, x)
            mul(y, x, x)
            mul(x, l, l)
            inc(l)
            add(x, x, l)
            mul(l, l, x)
            add(y, y, l)
        }
    }

    private fun pointAdd(x1: IntArray, y1: IntArray, x2: IntArray, y2: IntArray) {
        if (pointIsZero(x2, y2)) return
        if (pointIsZero(x1, y1)) {
            pointCopy(x1, y1, x2, y2)
            return
        }
        if (equal(x1, x2)) {
            if (equal(y1, y2)) pointDouble(x1, y1) else pointSetZero(x1, y1)
            return
        }
        val a = IntArray(NWORDS)
        val b = IntArray(NWORDS)
        val c = IntArray(NWORDS)
        val d = IntArray(NWORDS)
        add(a, y1, y2)
        add(b, x1, x2)
        inv(c, b)
        mul(c, c, a)
        mul(d, c, c)
        add(d, d, c)
        add(d, d, b)
        inc(d)
        add(x1, x1, d)
        mul(a, x1, c)
        add(a, a, d)
        add(y1, y1, a)
        copy(x1, d)
    }

    /** (x, y) := exp * (x, y), double-and-add. */
    internal fun pointMul(x: IntArray, y: IntArray, exp: IntArray) {
        val tmpx = IntArray(NWORDS)
        val tmpy = IntArray(NWORDS)
        val nbits = degree(exp)
        pointSetZero(tmpx, tmpy)
        for (i in nbits - 1 downTo 0) {
            pointDouble(tmpx, tmpy)
            if (getBit(exp, i) != 0) pointAdd(tmpx, tmpy, x, y)
        }
        pointCopy(x, y, tmpx, tmpy)
    }

    /** y^2 + x*y == x^3 + x^2 + b. */
    internal fun onCurve(x: IntArray, y: IntArray): Boolean {
        if (pointIsZero(x, y)) return false
        val a = IntArray(NWORDS)
        val b = IntArray(NWORDS)
        mul(a, x, x)
        mul(b, a, x)
        add(a, a, b)
        add(a, a, COEFF_B)
        mul(b, y, y)
        add(a, a, b)
        mul(b, x, y)
        return equal(a, b)
    }

    internal fun toWords(bytes: ByteArray, offset: Int): IntArray = IntArray(NWORDS) { i ->
        val p = offset + 4 * i
        (bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or
            ((bytes[p + 2].toInt() and 0xff) shl 16) or ((bytes[p + 3].toInt() and 0xff) shl 24)
    }

    internal fun toBytes(out: ByteArray, words: IntArray, offset: Int) {
        for (i in 0 until NWORDS) {
            val p = offset + 4 * i
            out[p] = words[i].toByte()
            out[p + 1] = (words[i] ushr 8).toByte()
            out[p + 2] = (words[i] ushr 16).toByte()
            out[p + 3] = (words[i] ushr 24).toByte()
        }
    }

    /** Clears the bits at and above degree(order) - 1, as the original (1 <= exp < n). */
    private fun clampScalar(k: IntArray) {
        val nbits = degree(BASE_ORDER)
        for (i in nbits - 1 until NWORDS * 32) clrBit(k, i)
    }

    /**
     * Public key (48 bytes) of [privateKey] (24 random bytes, not modified), or null when the
     * random number is too small (degree < 81): the caller then picks another one.
     */
    fun generatePublic(privateKey: ByteArray): ByteArray? {
        require(privateKey.size >= PRIVATE_KEY_SIZE) { "private key must be 24 bytes" }
        val k = toWords(privateKey, 0)
        if (degree(k) < CURVE_DEGREE / 2) return null
        clampScalar(k)
        val x = BASE_X.copyOf()
        val y = BASE_Y.copyOf()
        pointMul(x, y, k)
        return ByteArray(PUBLIC_KEY_SIZE).also {
            toBytes(it, x, 0)
            toBytes(it, y, NBYTES)
        }
    }

    /** Shared point (48 bytes, X then Y) or null when [othersPublic] is not a valid curve point. */
    fun generateShared(privateKey: ByteArray, othersPublic: ByteArray): ByteArray? {
        if (privateKey.size < PRIVATE_KEY_SIZE || othersPublic.size < PUBLIC_KEY_SIZE) return null
        val k = toWords(privateKey, 0)
        val x = toWords(othersPublic, 0)
        val y = toWords(othersPublic, NBYTES)
        if (pointIsZero(x, y) || !onCurve(x, y)) return null
        clampScalar(k)
        pointMul(x, y, k)
        return ByteArray(PUBLIC_KEY_SIZE).also {
            toBytes(it, x, 0)
            toBytes(it, y, NBYTES)
        }
    }
}
