package com.enjoyyourtime.globalfitness.band.xiaomi

fun hex(s: String): ByteArray {
    val clean = s.filterNot { it.isWhitespace() }
    return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

fun ByteArray.toHexString(): String = XiaomiCrypto.toHex(this)

fun range(from: Int, until: Int): ByteArray = ByteArray(until - from) { (from + it).toByte() }
