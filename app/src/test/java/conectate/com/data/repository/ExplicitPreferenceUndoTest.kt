package conectate.com.data.repository

import conectate.com.data.model.AlgorithmPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitPreferenceUndoTest {
    private val change = ExplicitPreferenceChange(
        accountId = "account-a",
        profileId = "profile-a",
        appliedProfileRevision = 3,
        surface = "video",
        dimension = "creator",
        subjectKey = "creator-a",
        appliedScore = -100,
        appliedId = "rule-a",
        appliedUpdatedAt = "2026-09-24T20:00:00Z",
        previous = null,
    )

    @Test fun `undo accepts only the exact rule revision that the action wrote`() {
        val applied = AlgorithmPreference(
            id = "rule-a", surface = "video", dimension = "creator",
            subjectKey = "creator-a", score = -100,
            updatedAt = "2026-09-24T20:00:00Z",
        )
        assertTrue(canUndoExplicitPreference(applied, change))
        assertFalse(canUndoExplicitPreference(applied.copy(score = 20), change))
        assertFalse(canUndoExplicitPreference(
            applied.copy(updatedAt = "2026-09-24T20:00:01Z"), change))
        assertFalse(canUndoExplicitPreference(applied.copy(id = "rule-b"), change))
        assertFalse(canUndoExplicitPreference(null, change))
        assertTrue(canUndoExplicitPreference(applied.copy(score = -30),
            change.copy(appliedScore = -30)))
    }
}
