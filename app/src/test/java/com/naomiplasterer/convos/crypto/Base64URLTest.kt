package com.naomiplasterer.convos.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Base64URLTest {

    @Test
    fun `encode returns URL-safe base64 string`() {
        val input = "Hello, World!".toByteArray()
        val encoded = Base64URL.encode(input)

        // Should not contain +, /, or = characters (URL-safe, no padding)
        assert(!encoded.contains('+'))
        assert(!encoded.contains('/'))
        assert(!encoded.contains('='))

        // Should be able to decode back
        val decoded = Base64URL.decode(encoded)
        assertArrayEquals(input, decoded)
    }

    @Test
    fun `decode returns original bytes`() {
        val original = "Test data 123!@#".toByteArray()
        val encoded = Base64URL.encode(original)
        val decoded = Base64URL.decode(encoded)

        assertArrayEquals(original, decoded)
    }

    @Test
    fun `encode and decode with empty byte array`() {
        val empty = ByteArray(0)
        val encoded = Base64URL.encode(empty)
        val decoded = Base64URL.decode(encoded)

        assertArrayEquals(empty, decoded)
        assertEquals("", encoded)
    }

    @Test
    fun `encode and decode with special characters`() {
        val special = "Special: \n\t\r ñ é ü 中文 🚀".toByteArray()
        val encoded = Base64URL.encode(special)
        val decoded = Base64URL.decode(encoded)

        assertArrayEquals(special, decoded)
        assertEquals(String(special), String(decoded))
    }

    @Test
    fun `encode produces URL-safe output for binary data`() {
        // Create binary data that would produce + and / in standard Base64
        val binary = byteArrayOf(
            0x00, 0x3F.toByte(), 0xFF.toByte(),
            0xFE.toByte(), 0xF0.toByte(), 0xAA.toByte()
        )
        val encoded = Base64URL.encode(binary)

        // Verify it's URL-safe (only contains A-Z, a-z, 0-9, -, _)
        val urlSafePattern = Regex("^[A-Za-z0-9_-]*$")
        assert(urlSafePattern.matches(encoded)) {
            "Encoded string '$encoded' contains non-URL-safe characters"
        }

        // Verify roundtrip
        val decoded = Base64URL.decode(encoded)
        assertArrayEquals(binary, decoded)
    }
}
