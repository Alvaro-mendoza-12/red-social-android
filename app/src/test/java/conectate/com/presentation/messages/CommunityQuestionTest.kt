package conectate.com.presentation.messages
import org.junit.Assert.*
import org.junit.Test
class CommunityQuestionTest {
    @Test fun answerLimitPreservesEmojiCodePoints() {
        val answer = "😀".repeat(41)
        val shortened=limitedQuestionAnswer(answer,40)
        assertEquals(40,shortened.codePointCount(0,shortened.length))
        assertEquals("😀".repeat(40),shortened)
        assertEquals("5",limitedQuestionAnswer("5",20))
    }
}
