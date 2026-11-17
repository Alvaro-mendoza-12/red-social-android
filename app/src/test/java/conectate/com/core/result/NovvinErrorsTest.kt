package conectate.com.core.result

import conectate.com.core.network.ReadDeadlineExceededException
import conectate.com.presentation.common.isOfflineAccessibleRoute
import conectate.com.presentation.common.toUserFacingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class NovvinErrorsTest {
    @Test
    fun everyHttpStatusResolvesFromATypedFailureWithoutRenderingItsPayload() {
        val expected = linkedMapOf(
            400 to ErrorCharacter.Confused, 401 to ErrorCharacter.Guardian,
            403 to ErrorCharacter.Protector, 404 to ErrorCharacter.Detective,
            408 to ErrorCharacter.Sleeper, 409 to ErrorCharacter.Confused,
            422 to ErrorCharacter.Confused, 429 to ErrorCharacter.Protector,
            500 to ErrorCharacter.Overloaded, 502 to ErrorCharacter.Overloaded,
            503 to ErrorCharacter.Sleeper, 504 to ErrorCharacter.Sleeper
        )
        assertEquals(expected.keys.toList(), NovvinErrors.catalog.map { it.code })
        expected.forEach { (code, character) ->
            val failure = HttpStatusFailure(code, "secret https://internal.invalid/private")
            val problem = checkNotNull(NovvinErrors.from(Exception("outer", failure)))
            assertEquals(code, problem.code)
            assertEquals(character, problem.character)
            assertTrue(problem.narration.startsWith(if (code < 500) "Error cuatrocientos" else "Error quinientos"))
            assertFalse(problem.narration.contains("internal.invalid"))
            assertFalse(failure.toUserFacingMessage().contains("internal.invalid"))
        }
    }

    @Test
    fun warningCriticalAndConnectivityHaveDifferentPresentation() {
        assertEquals(ErrorLevel.Notice, NovvinErrors[429]?.level)
        assertEquals(ErrorLevel.Critical, NovvinErrors[500]?.level)
        assertEquals(ErrorLevel.Connection, NovvinErrors[503]?.level)
        assertSame(NovvinErrors[408], NovvinErrors.from(ReadDeadlineExceededException(1000)))
        assertSame(NovvinErrors.offline, NovvinErrors.from(UnknownHostException("internal.invalid")))
        assertNull(NovvinErrors.from(HttpStatusFailure(418, "")))
        assertNull(NovvinErrors.unexpected.code)
        assertTrue(isOfflineAccessibleRoute("error_characters"))
    }
}
