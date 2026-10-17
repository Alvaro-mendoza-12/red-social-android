package conectate.com.presentation.profile
import org.junit.Assert.*
import org.junit.Test
class ProfileBirthdayTest {
 @Test fun `presentation shows day and month without year`() {
  assertEquals("17 de octubre",profileBirthdayLabel("2000-10-17"))
  assertNull(profileBirthdayLabel("2000-02-31"));assertNull(profileBirthdayLabel(null))
 }
}
