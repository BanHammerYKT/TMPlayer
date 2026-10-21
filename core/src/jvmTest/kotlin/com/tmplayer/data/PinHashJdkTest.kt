package com.tmplayer.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The pure PIN hash is the JDK's PBKDF2WithHmacSHA256, so a PIN set on one device checks on the other. */
class PinHashJdkTest {
    @Test
    fun `a line the JDK would have written still verifies`() {
        val salt = ByteArray(16) { it.toByte() }
        val spec = PBEKeySpec("2580".toCharArray(), salt, 20_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val e = Base64.getEncoder()
        val line = listOf("v1", "20000", e.encodeToString(salt), e.encodeToString(key)).joinToString(":")
        assertTrue(PinHash.verify("2580", line))
        assertTrue(PinHash.hash("2580", salt) == line)
    }
}
