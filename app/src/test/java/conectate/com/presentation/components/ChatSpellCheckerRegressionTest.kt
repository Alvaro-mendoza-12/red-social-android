package conectate.com.presentation.components

import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class ChatSpellCheckerRegressionTest {
    @Test fun punctuationAndClearingASentDraftIgnoreEmptyAndLateSystemResults() {
        val checker = ChatSpellChecker(ApplicationProvider.getApplicationContext())
        try {
            listOf(".", "!", "?", "…", "😀", "a", "texto con foto").forEach { text ->
                checker.invalidate()
                checker.check(text)
                checker.onGetSentenceSuggestions(arrayOf(null))
                checker.invalidate() // Sending clears the composer before its callback arrives.
                checker.check("")
                checker.onGetSentenceSuggestions(arrayOf(null, typo(sequence = 0)))
                assertTrue("Late suggestions cannot restore a sent draft", checker.issues.isEmpty())
            }
        } finally { checker.close() }
    }
    private fun typo(start: Int = 0, length: Int = 5, sequence: Int = 0): SentenceSuggestionsInfo =
        SentenceSuggestionsInfo(arrayOf(SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO,
            arrayOf("hola"), 0, sequence)), intArrayOf(start), intArrayOf(length))

    @Test fun nullEntriesReportedByAndroidCannotCrashEmojiTypingOrReopening() {
        val checker = ChatSpellChecker(ApplicationProvider.getApplicationContext())
        try {
            checker.check("😀 👩🏽‍💻 🇵🇪")
            checker.onGetSentenceSuggestions(null)
            checker.onGetSentenceSuggestions(arrayOf(null, null))
            assertTrue(checker.issues.isEmpty())
            checker.check("holaa 👩🏽‍💻")
            checker.onGetSentenceSuggestions(arrayOf(null, typo(), null))
            assertEquals("holaa", checker.issues.single().word)
            assertEquals(listOf("hola"), checker.issues.single().suggestions)
        } finally { checker.close() }
    }

    @Test fun invalidRangesAndLateCallbacksAfterClosureCannotAffectTheEditor() {
        val checker = ChatSpellChecker(ApplicationProvider.getApplicationContext())
        checker.check("holaa 👩🏽‍💻")
        checker.onGetSentenceSuggestions(arrayOf(typo(-1), typo(1, Int.MAX_VALUE), typo(8, 1)))
        assertTrue(checker.issues.isEmpty())
        checker.invalidate()
        checker.onGetSentenceSuggestions(arrayOf(typo(sequence = 0)))
        assertTrue(checker.issues.isEmpty())
        checker.close()
        checker.onGetSentenceSuggestions(arrayOf(null, typo(sequence = 1)))
        assertTrue(checker.issues.isEmpty())
    }
}
