package conectate.com.presentation.posts

import conectate.com.data.model.Post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PostReactionWriterTest {
    private val original = Post(id="p",userId="author",userName="Author",likeCount=2,reactionCounts=mapOf("LIKE" to 2))
    @Test fun successfulReactionRefreshesCanonicalTotals() = runTest {
        var post=original; var wrote:String?=null
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,type,_->wrote=type;Result.success(Unit)},
            {Result.success(original.copy(likedByMe=true,myReaction="LOVE",likeCount=4,reactionCounts=mapOf("LIKE" to 3,"LOVE" to 1)))},{post=it},{fail()})
        writer.set("p","love");assertEquals(3,post.likeCount);runCurrent()
        assertEquals("LOVE",wrote);assertEquals(4,post.likeCount);assertEquals("LOVE",post.myReaction)
    }
    @Test fun failedWriteRollsBackAndOffersRetry() = runTest {
        var post=original;var failures=0
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->Result.failure(Exception())},
            {fail();Result.success(original)},{post=it},{failures++})
        writer.set("p","LIKE");runCurrent();assertEquals(original,post);assertEquals(1,failures)
        writer.set("p","LIKE");runCurrent();assertEquals(2,failures)
    }
    @Test fun failedRefreshDoesNotUndoAStoredReaction() = runTest {
        var post=original
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->Result.success(Unit)},
            {throw IllegalStateException()},{post=it},{fail()})
        writer.set("p","LIKE");runCurrent();assertTrue(post.likedByMe);assertEquals(3,post.likeCount)
    }
    @Test fun rapidChangesBeforeTheFirstWriteAreCoalesced() = runTest {
        var post=original;var writes=0;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->writes++;wait.await()},
            {Result.failure(Exception())},{post=it},{fail()})
        writer.set("p","LIKE");writer.set("p",null);runCurrent();assertEquals(0,writes)
        assertFalse(post.likedByMe)
    }
    @Test fun aSecondTapDuringASlowWriteIsImmediateAndEventuallyStored() = runTest {
        var post=original;val writes=mutableListOf<String?>();val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,type,_->writes+=type;if(writes.size==1)wait.await() else Result.success(Unit)},
            {Result.failure(Exception())},{post=it},{fail()})
        writer.set("p","LIKE");runCurrent();writer.set("p",null)
        assertFalse(post.likedByMe);assertEquals(2,post.likeCount)
        wait.complete(Result.success(Unit));runCurrent()
        assertEquals(listOf("LIKE",null),writes);assertFalse(post.likedByMe)
    }
    @Test fun anOldFeedReadCannotUndoANewlyAcknowledgedReaction() = runTest {
        var post=original
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->Result.success(Unit)},
            {Result.failure(Exception())},{post=it},{fail()})
        val read=writer.readVersion()
        writer.set("p","LIKE");runCurrent()
        assertTrue(writer.reconcile(original,read).likedByMe)
        // A genuinely newer server read may include a change from another device.
        assertFalse(writer.reconcile(original,writer.readVersion()).likedByMe)
    }
    @Test fun backgroundReadsKeepOtherPeopleCountsWhileProtectingThePendingChoice() = runTest {
        var post=original;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->wait.await()},
            {Result.failure(Exception())},{post=it},{fail()})
        writer.set("p","LOVE");runCurrent()
        val remote=original.copy(likeCount=8,reactionCounts=mapOf("LIKE" to 8))
        val merged=writer.reconcile(remote,writer.readVersion())
        assertEquals(9,merged.likeCount);assertEquals("LOVE",merged.myReaction)
        writer.clear()
    }
    @Test fun aFailedWriteDoesNotUndoConcurrentContentChanges() = runTest {
        var post=original;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->wait.await()},
            {Result.failure(Exception())},{post=it},{})
        writer.set("p","LOVE");runCurrent();post=post.copy(content="edited while saving")
        wait.complete(Result.failure(Exception()));runCurrent()
        assertEquals("edited while saving",post.content);assertFalse(post.likedByMe)
    }
    @Test fun lateSuccessCannotPublishIntoAnotherAccount() = runTest {
        var post=original;var owner="first";var reads=0;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{owner},{post},{_,_,_->wait.await()},
            {reads++;Result.success(original)},{post=it},{fail()})
        writer.set("p","LIKE");runCurrent();owner="second";post=original
        wait.complete(Result.success(Unit));runCurrent();assertEquals(original,post);assertEquals(0,reads)
    }
    @Test fun lateFailureCannotRollbackAnotherAccount() = runTest {
        var post=original;var owner="first";var failures=0;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{owner},{post},{_,_,_->wait.await()},
            {Result.success(original)},{post=it},{failures++})
        writer.set("p","LIKE");runCurrent();owner="second";post=original.copy(content="another account")
        wait.complete(Result.failure(Exception()));runCurrent();assertEquals("another account",post.content);assertEquals(0,failures)
    }
    @Test fun invalidReactionAndSignedOutStateDoNotPublishOrWrite() = runTest {
        var owner:String?="account";var changes=0;var writes=0
        val writer=PostReactionWriter(backgroundScope,{owner},{original},{_,_,_->writes++;Result.success(Unit)},
            {Result.success(original)},{changes++},{fail()})
        writer.set("p","INVALID");owner=null;writer.set("p","LIKE");runCurrent();assertEquals(0,changes);assertEquals(0,writes)
    }
    @Test fun tappingExistingReactionRemovesIt() = runTest {
        var post=original.withReaction("LOVE");var written="unwritten";var wasReacted=false
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,type,previous->written=type?:"removed";wasReacted=previous;Result.success(Unit)},
            {Result.failure(Exception())},{post=it},{fail()})
        writer.set("p","LOVE");runCurrent();assertEquals("removed",written);assertTrue(wasReacted);assertFalse(post.likedByMe);assertEquals(2,post.likeCount)
    }
    @Test fun clearingCancelsOldWorkAndAllowsTheNextAccountToReact() = runTest {
        var post=original;var writes=0;val wait=CompletableDeferred<Result<Unit>>()
        val writer=PostReactionWriter(backgroundScope,{"account"},{post},{_,_,_->writes++;if(writes==1)wait.await()else Result.success(Unit)},
            {Result.failure(Exception())},{post=it},{fail()})
        writer.set("p","LIKE");runCurrent();writer.clear();post=original
        writer.set("p","LOVE");runCurrent();assertEquals(2,writes);assertEquals("LOVE",post.myReaction)
        wait.complete(Result.success(Unit));runCurrent();assertEquals("LOVE",post.myReaction)
    }
    @Test fun lateRefreshCannotPublishIntoAnotherAccount() = runTest {
        var post=original;var owner="first";val wait=CompletableDeferred<Result<Post>>()
        val writer=PostReactionWriter(backgroundScope,{owner},{post},{_,_,_->Result.success(Unit)},
            {wait.await()},{post=it},{fail()})
        writer.set("p","LIKE");runCurrent();owner="second";post=original.copy(content="second")
        wait.complete(Result.success(original.copy(likeCount=99)));runCurrent()
        assertEquals("second",post.content);assertEquals(2,post.likeCount)
    }
}
