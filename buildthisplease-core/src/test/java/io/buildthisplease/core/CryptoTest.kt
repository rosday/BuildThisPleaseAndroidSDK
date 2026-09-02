package io.buildthisplease.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun `DER signature becomes fixed-width JOSE signature`() {
        val r = byteArrayOf(0, 0x80.toByte()) + ByteArray(31) { 1 }
        val s = byteArrayOf(2) + ByteArray(31) { 3 }
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        val der = byteArrayOf(0x30, body.size.toByte()) + body

        val raw = derEcdsaToRaw(der)

        assertEquals(64, raw.size)
        assertArrayEquals(r.copyOfRange(1, r.size), raw.copyOfRange(0, 32))
        assertArrayEquals(s, raw.copyOfRange(32, 64))
    }

    @Test
    fun `malformed DER signature is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            derEcdsaToRaw(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `installation key identifier is derived from the public key`() {
        val first = installationKeyId(byteArrayOf(1, 2, 3))
        val second = installationKeyId(byteArrayOf(1, 2, 4))

        assertEquals(first, installationKeyId(byteArrayOf(1, 2, 3)))
        assertTrue(first.startsWith("p256_"))
        assertNotEquals(first, second)
    }
}
