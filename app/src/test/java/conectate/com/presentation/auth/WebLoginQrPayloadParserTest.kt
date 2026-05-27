package conectate.com.presentation.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebLoginQrPayloadParserTest {

    @Test
    fun `parse custom scheme qr payload`() {
        val parsed = WebLoginQrPayloadParser.parse(
            "conectate://web-login?pairId=abc-123&nonce=nonce-456"
        )

        assertNotNull(parsed)
        assertEquals("abc-123", parsed?.pairId)
        assertEquals("nonce-456", parsed?.nonce)
    }

    @Test
    fun `parse plain query payload`() {
        val parsed = WebLoginQrPayloadParser.parse(
            "pairId=pair-1&nonce=nonce-2"
        )

        assertNotNull(parsed)
        assertEquals("pair-1", parsed?.pairId)
        assertEquals("nonce-2", parsed?.nonce)
    }

    @Test
    fun `reject invalid qr payload`() {
        assertNull(WebLoginQrPayloadParser.parse("https://example.com"))
        assertNull(WebLoginQrPayloadParser.parse("solo texto"))
    }
}
