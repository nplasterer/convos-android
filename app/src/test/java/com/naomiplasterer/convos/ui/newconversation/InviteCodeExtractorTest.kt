package com.naomiplasterer.convos.ui.newconversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for invite code extraction logic from various URL formats.
 * This mirrors the logic in NewConversationViewModel.extractInviteCode()
 */
class InviteCodeExtractorTest {

    private fun extractInviteCode(qrCodeData: String): String? {
        return when {
            // Android format: convos://i/{code}
            qrCodeData.startsWith("convos://i/") -> {
                qrCodeData.removePrefix("convos://i/")
            }
            // Android format: https://convos.app/i/{code}
            qrCodeData.startsWith("https://convos.app/i/") -> {
                qrCodeData.removePrefix("https://convos.app/i/")
            }
            // iOS format with query param: https://{domain}/v2?i={code}
            qrCodeData.contains("/v2?i=") -> {
                val regex = Regex("/v2\\?i=([^&]+)")
                val match = regex.find(qrCodeData)
                match?.groupValues?.getOrNull(1)
            }
            // iOS format with path: https://popup.convos.org/{conversationId}
            // Also matches: https://convos.app/v2?i={code} style URLs
            qrCodeData.startsWith("https://") || qrCodeData.startsWith("http://") -> {
                try {
                    // Extract the path after the domain
                    val url = qrCodeData.removePrefix("https://").removePrefix("http://")
                    val pathStart = url.indexOf('/')
                    if (pathStart >= 0) {
                        url.substring(pathStart + 1)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            // Raw invite code (base64url or alphanumeric characters)
            qrCodeData.matches(Regex("^[a-zA-Z0-9-_]+$")) -> {
                qrCodeData
            }
            else -> null
        }
    }

    @Test
    fun `extract invite code from Android deep link format`() {
        val qrCode = "convos://i/abc123XYZ"
        val result = extractInviteCode(qrCode)

        assertEquals("abc123XYZ", result)
    }

    @Test
    fun `extract invite code from Android HTTPS format`() {
        val qrCode = "https://convos.app/i/test-invite-code_123"
        val result = extractInviteCode(qrCode)

        assertEquals("test-invite-code_123", result)
    }

    @Test
    fun `extract invite code from iOS v2 query param format`() {
        val qrCode = "https://popup.convos.org/v2?i=my_invite_code_456"
        val result = extractInviteCode(qrCode)

        assertEquals("my_invite_code_456", result)
    }

    @Test
    fun `extract invite code from iOS v2 format with additional query params`() {
        val qrCode = "https://popup.convos.org/v2?i=invite123&other=param"
        val result = extractInviteCode(qrCode)

        assertEquals("invite123", result)
    }

    @Test
    fun `extract invite code from generic HTTPS URL with path`() {
        val qrCode = "https://popup.convos.org/some-conversation-id-789"
        val result = extractInviteCode(qrCode)

        assertEquals("some-conversation-id-789", result)
    }

    @Test
    fun `extract invite code from HTTP URL with path`() {
        val qrCode = "http://example.com/invite-code-xyz"
        val result = extractInviteCode(qrCode)

        assertEquals("invite-code-xyz", result)
    }

    @Test
    fun `extract raw invite code`() {
        val qrCode = "raw_invite_code_12345"
        val result = extractInviteCode(qrCode)

        assertEquals("raw_invite_code_12345", result)
    }

    @Test
    fun `extract raw base64url invite code`() {
        val qrCode = "aGVsbG8td29ybGQ_test-123_ABC"
        val result = extractInviteCode(qrCode)

        assertEquals("aGVsbG8td29ybGQ_test-123_ABC", result)
    }

    @Test
    fun `extract invite code from URL with long path`() {
        val qrCode = "https://convos.app/i/very-long-invite-code-with-many-characters-1234567890"
        val result = extractInviteCode(qrCode)

        assertEquals("very-long-invite-code-with-many-characters-1234567890", result)
    }

    @Test
    fun `return null for URL without path`() {
        val qrCode = "https://popup.convos.org"
        val result = extractInviteCode(qrCode)

        assertNull(result)
    }

    @Test
    fun `return null for invalid format with special characters`() {
        val qrCode = "invalid@code#with\$special%chars"
        val result = extractInviteCode(qrCode)

        assertNull(result)
    }

    @Test
    fun `return null for empty string`() {
        val qrCode = ""
        val result = extractInviteCode(qrCode)

        assertNull(result)
    }

    @Test
    fun `return null for malformed URL`() {
        val qrCode = "not a valid url or code!"
        val result = extractInviteCode(qrCode)

        assertNull(result)
    }

    @Test
    fun `extract code from v2 format without additional params`() {
        val qrCode = "https://popup.convos.org/v2?i=simple_code"
        val result = extractInviteCode(qrCode)

        assertEquals("simple_code", result)
    }

    @Test
    fun `handle URL with trailing slash`() {
        val qrCode = "https://convos.app/i/code123/"
        val result = extractInviteCode(qrCode)

        assertEquals("code123/", result)
    }

    @Test
    fun `extract from Android deep link with dashes and underscores`() {
        val qrCode = "convos://i/test_code-with-dash_123"
        val result = extractInviteCode(qrCode)

        assertEquals("test_code-with-dash_123", result)
    }

    @Test
    fun `extract from URL with subdomain`() {
        val qrCode = "https://staging.popup.convos.org/invite-code-abc"
        val result = extractInviteCode(qrCode)

        assertEquals("invite-code-abc", result)
    }

    @Test
    fun `handle case sensitive URLs`() {
        val qrCode = "https://Convos.App/i/TestCode123"
        val result = extractInviteCode(qrCode)

        // Should not match the convos.app format due to case sensitivity
        assertEquals("i/TestCode123", result)
    }

    @Test
    fun `raw code with only numbers`() {
        val qrCode = "1234567890"
        val result = extractInviteCode(qrCode)

        assertEquals("1234567890", result)
    }

    @Test
    fun `raw code with only letters`() {
        val qrCode = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val result = extractInviteCode(qrCode)

        assertEquals("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", result)
    }
}
