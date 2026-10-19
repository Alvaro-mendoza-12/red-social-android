package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class QuestionCloudMotionTest {
    @Test fun automaticChoiceRespectsSystemAndExplicitChoiceCanEnableMotion() {
        assertFalse(cloudMotionRequested(CloudMotionChoice.AUTO, false))
        assertTrue(cloudMotionRequested(CloudMotionChoice.AUTO, true))
        assertTrue(cloudMotionRequested(CloudMotionChoice.ON, false))
        assertFalse(cloudMotionRequested(CloudMotionChoice.OFF, true))
    }

    @Test fun backgroundAndAnswerDialogStopTheFrameLoop() {
        assertTrue(shouldMoveQuestionClouds(true, true, false))
        assertFalse(shouldMoveQuestionClouds(true, false, false))
        assertFalse(shouldMoveQuestionClouds(true, true, true))
        assertFalse(shouldMoveQuestionClouds(false, true, false))
    }

    @Test fun independentPathsMoveBothAxesAndStayInReservedMargins() {
        for (slot in 0..2) {
            val points = (0..1000).map { questionCloudDrift(it / 1000f, slot) }
            assertTrue(points.all { abs(it.x) <= 18.01f && abs(it.y) <= 12.01f })
            assertTrue(points.maxOf { it.x } - points.minOf { it.x } > 30f)
            assertTrue(points.maxOf { it.y } - points.minOf { it.y } > 18f)
            assertEquals(points.first().x, points.last().x, .001f)
            assertEquals(points.first().y, points.last().y, .001f)
        }
        assertNotEquals(questionCloudDrift(.25f, 0), questionCloudDrift(.25f, 1))
    }
}
