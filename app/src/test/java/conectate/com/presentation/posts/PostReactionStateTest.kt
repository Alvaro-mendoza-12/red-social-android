package conectate.com.presentation.posts
import conectate.com.data.model.Post
import org.junit.Assert.*
import org.junit.Test
class PostReactionStateTest {
    private fun post() = Post(id="p",userId="owner",userName="Owner",likeCount=2,reactionCounts=mapOf("LIKE" to 2))
    @Test fun likeUpdatesBothDisplayedTotalsAndTypedCounts() {
        val p=post().withReaction("LIKE");assertEquals(3,p.likeCount);assertEquals(3,p.reactionCounts["LIKE"]);assertTrue(p.likedByMe);assertEquals("LIKE",p.myReaction)
    }
    @Test fun unlikeKeepsOtherPeoplesLikes() {
        val p=post().copy(likedByMe=true,myReaction="LIKE").withReaction(null);assertEquals(1,p.likeCount);assertEquals(1,p.reactionCounts["LIKE"]);assertFalse(p.likedByMe)
    }
    @Test fun changingEmojiDoesNotAddAnExtraPerson() {
        val p=post().copy(likedByMe=true,myReaction="LIKE").withReaction("LOVE");assertEquals(2,p.likeCount);assertEquals(mapOf("LIKE" to 1,"LOVE" to 1),p.reactionCounts)
    }
    @Test fun legacyCountWithoutBreakdownIsRetained() {
        val p=post().copy(reactionCounts=emptyMap()).withReaction("LIKE");assertEquals(3,p.likeCount)
    }
    @Test fun lastUnlikeDoesNotProduceNegativeOrStaleCount() {
        val p=post().copy(likeCount=1,reactionCounts=mapOf("LIKE" to 1),myReaction="LIKE",likedByMe=true).withReaction(null)
        assertEquals(0,p.likeCount);assertTrue(p.reactionCounts.isEmpty())
    }
    @Test fun reactionDoesNotClearSensitiveMediaFlag() {
        val p=post().apply { isSensitive=true };assertTrue(p.withReaction("LIKE").isSensitive)
    }
}
