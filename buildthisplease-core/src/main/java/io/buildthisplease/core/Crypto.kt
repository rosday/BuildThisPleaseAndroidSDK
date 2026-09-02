package io.buildthisplease.core

import android.util.Base64
import java.security.MessageDigest

internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
internal fun String.sha256Base64Url(): String = encodeBase64Url(toByteArray().sha256())
internal fun ByteArray.sha256Base64Url(): String = encodeBase64Url(sha256())
internal fun encodeBase64Url(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
internal fun decodeBase64Url(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

internal fun derEcdsaToRaw(signature: ByteArray, componentSize: Int = 32): ByteArray {
    require(signature.size >= 8 && signature[0] == 0x30.toByte()) { "Invalid DER ECDSA signature" }
    var offset = 1
    val (_, sequenceOffset) = readDerLength(signature, offset)
    offset = sequenceOffset
    require(signature[offset++] == 0x02.toByte())
    val (rLength, rOffset) = readDerLength(signature, offset)
    offset = rOffset
    val r = signature.copyOfRange(offset, offset + rLength)
    offset += rLength
    require(signature[offset++] == 0x02.toByte())
    val (sLength, sOffset) = readDerLength(signature, offset)
    val s = signature.copyOfRange(sOffset, sOffset + sLength)
    return unsignedComponent(r, componentSize) + unsignedComponent(s, componentSize)
}

private fun readDerLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
    val first = bytes[offset].toInt() and 0xff
    if (first and 0x80 == 0) return first to offset + 1
    val count = first and 0x7f
    require(count in 1..2 && offset + count < bytes.size)
    var length = 0
    repeat(count) { length = (length shl 8) or (bytes[offset + 1 + it].toInt() and 0xff) }
    return length to offset + 1 + count
}

private fun unsignedComponent(bytes: ByteArray, size: Int): ByteArray {
    val value = bytes.dropWhile { it == 0.toByte() }.toByteArray()
    require(value.size <= size)
    return ByteArray(size - value.size) + value
}
