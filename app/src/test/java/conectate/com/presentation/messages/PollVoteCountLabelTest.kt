package conectate.com.presentation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class PollVoteCountLabelTest {
    @Test fun singularAndPluralVotes() {
        assertEquals("0 votos", pollVoteCountLabel(0))
        assertEquals("1 voto", pollVoteCountLabel(1))
        assertEquals("2 votos", pollVoteCountLabel(2))
    }
}
