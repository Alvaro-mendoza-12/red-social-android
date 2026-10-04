package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentMusicClipOptionsTest {
    @Test fun fullMinuteHasThreeChoices() = assertEquals(listOf(15_000L, 30_000L, 60_000L), momentMusicClipDurations(60_000L))
    @Test fun videoDurationCapsAndDeduplicatesChoices() = assertEquals(listOf(15_000L, 25_000L), momentMusicClipDurations(25_000L))
    @Test fun sixSecondPhotoHasOneChoice() = assertEquals(listOf(6_000L), momentMusicClipDurations(6_000L))
    @Test fun aLongTrackDoesNotOfferMoreThanOneMinute() = assertEquals(listOf(15_000L, 30_000L, 60_000L), momentMusicClipDurations(120_000L))
    @Test fun absentDurationHasNoOptions() = assertEquals(emptyList<Long>(), momentMusicClipDurations(0L))
}
