package conectate.com.presentation.purpose

import java.time.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PurposePolicyTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val community = "22222222-2222-4222-8222-222222222222"
    @Test fun schedulingRejectsSkippedAndAmbiguousLocalTimesInsteadOfSilentlyChangingTheHour() {
        val zone = ZoneId.of("America/New_York")
        assertThrows(IllegalArgumentException::class.java) { purposeScheduledInstant(LocalDate.of(2026,3,8),LocalTime.of(2,30),zone) }
        assertThrows(IllegalArgumentException::class.java) { purposeScheduledInstant(LocalDate.of(2026,11,1),LocalTime.of(1,30),zone) }
        assertEquals(Instant.parse("2026-11-05T00:30:00Z"),purposeScheduledInstant(LocalDate.of(2026,11,4),LocalTime.of(19,30),ZoneId.of("America/Lima")))
    }
    @Test fun unicodeLimitsDoNotSplitAnEmojiAndRecoveryKeysSurviveProposalRecreation() {
        assertEquals("ab😀",purposeLimit("ab😀c",3))
        val first = PurposeTarget("proposal",actor,community)
        val second = PurposeTarget("proposal",actor,"33333333-3333-4333-8333-333333333333")
        assertEquals(first.key,second.key); assertNotEquals(first.writePath(null),second.writePath(null))
    }
    @Test fun aQuestionWithoutItsMessageDestinationIsRejectedBeforeItCanRenderAnAction() {
        val payload = buildJsonObject {
            put("communityId",community); put("serverTime","2026-11-04T22:00:00Z")
            put("targets",buildJsonArray { add(buildJsonObject { put("kind","question"); put("id",actor); put("title","Pregunta") }) })
        }
        assertThrows(IllegalArgumentException::class.java) { validatePurposePayload(PurposeTarget("community",community),payload,actor) }
    }
}
