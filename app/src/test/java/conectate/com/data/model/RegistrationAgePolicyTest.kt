package conectate.com.data.model

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RegistrationAgePolicyTest {
    private val today = LocalDate.of(2026, 10, 30)
    @Test fun `thirteenth birthday is allowed`() {
        assertTrue(isRegistrationBirthDateAllowed(LocalDate.of(2013, 10, 30), today))
    }
    @Test fun `day before thirteenth birthday is rejected`() {
        assertFalse(isRegistrationBirthDateAllowed(LocalDate.of(2013, 10, 31), today))
    }
    @Test fun `newborn and future dates are rejected`() {
        assertFalse(isRegistrationBirthDateAllowed(today, today))
        assertFalse(isRegistrationBirthDateAllowed(today.plusDays(1), today))
    }
    @Test fun `adult registration is allowed`() {
        assertTrue(isRegistrationBirthDateAllowed(LocalDate.of(2000, 1, 1), today))
    }
    @Test fun `leap birthday is evaluated against the actual calendar`() {
        val leapBirth = LocalDate.of(2012, 2, 29)
        assertFalse(isRegistrationBirthDateAllowed(leapBirth, LocalDate.of(2025, 2, 28)))
        assertTrue(isRegistrationBirthDateAllowed(leapBirth, LocalDate.of(2025, 3, 1)))
    }
}
