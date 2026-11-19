package conectate.com.presentation.profile

import java.time.MonthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileBirthdayTest {
    @Test fun aSharedLegacyDateDisplaysDayAndMonth() {
        val birthday = visibleProfileBirthday("2000-10-17", "everyone", isOwnProfile = false)
        assertEquals("17 de octubre", birthday?.format(profileBirthdayFormatter))
    }

    @Test fun aSharedReducedDateWorksWithoutAYear() {
        assertEquals(MonthDay.of(2, 29), visibleProfileBirthday("--02-29", "followers", isOwnProfile = false))
    }

    @Test fun anExplicitlyPrivateDateStaysHiddenEvenWhenTheResponseSuppliedIt() {
        assertNull(visibleProfileBirthday("2000-10-17", "only_me", isOwnProfile = false))
    }

    @Test fun aLegacyNobodySettingAlsoHidesTheDate() {
        assertNull(visibleProfileBirthday("2000-10-17", "Nobody", isOwnProfile = false))
    }

    @Test fun theOwnerCanStillSeeTheirOwnPrivateBirthday() {
        assertEquals(MonthDay.of(10, 17), visibleProfileBirthday("2000-10-17", "nobody", isOwnProfile = true))
    }

    @Test fun absentAndInvalidDatesStayHidden() {
        assertNull(visibleProfileBirthday("2000-02-31", null, isOwnProfile = false))
        assertNull(visibleProfileBirthday("--02-30", null, isOwnProfile = false))
        assertNull(visibleProfileBirthday(null, "everyone", isOwnProfile = false))
    }
}
