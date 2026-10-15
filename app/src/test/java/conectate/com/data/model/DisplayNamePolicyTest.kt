package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
class DisplayNamePolicyTest {
    @Test fun boundariesAreTwoThroughTwentyUnicodeCharacters() {
        assertFalse(DisplayNamePolicy.valid("a"));assertTrue(DisplayNamePolicy.valid("Ab"))
        assertTrue(DisplayNamePolicy.valid("a".repeat(20)));assertFalse(DisplayNamePolicy.valid("a".repeat(21)))
    }
    @Test fun unicodeInputNeverCutsASurrogatePair() {
        val input = "😀".repeat(21);val limited=DisplayNamePolicy.input(input)
        assertEquals("😀".repeat(20),limited);assertEquals(20,DisplayNamePolicy.length(limited))
    }
    @Test fun normalizedAccentsCountOnceAndControlsAreRejected() {
        assertEquals("José",DisplayNamePolicy.normalized(" Jose\u0301 "))
        assertEquals(4,DisplayNamePolicy.length("Jose\u0301"));assertFalse(DisplayNamePolicy.valid("Ab\nCd"))
    }
}
