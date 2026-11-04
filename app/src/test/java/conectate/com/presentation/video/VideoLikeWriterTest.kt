package conectate.com.presentation.video

import conectate.com.data.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoLikeWriterTest {
    private val original = Video(id = "video", likeCount = 4)

    @Test fun firstTapWritesWithoutADebounce() = runTest {
        var video = original
        val writes = mutableListOf<Boolean>()
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, target ->
            writes += target; Result.success(Unit)
        }, { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        writer.toggle(video.id)
        assertTrue(video.likedByMe)
        runCurrent()
        assertEquals(listOf(true), writes)
        assertEquals(5, video.likeCount)
    }

    @Test fun secondTapDuringSlowWriteSendsTheLatestChoiceWithoutCancellingTheFirst() = runTest {
        var video = original
        val firstResponse = CompletableDeferred<Result<Unit>>()
        val writes = mutableListOf<Boolean>()
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, target ->
            writes += target
            if (writes.size == 1) firstResponse.await() else Result.success(Unit)
        }, { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        writer.toggle(video.id); runCurrent()
        writer.toggle(video.id)
        assertFalse(video.likedByMe)
        assertEquals(4, video.likeCount)
        firstResponse.complete(Result.success(Unit)); runCurrent()
        assertEquals(listOf(true, false), writes)
        assertFalse(video.likedByMe)
    }

    @Test fun failedWritePreservesUnrelatedUpdates() = runTest {
        var video = original
        val response = CompletableDeferred<Result<Unit>>()
        var errors = 0
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, _ -> response.await() },
            { _, liked -> video = video.withVideoLike(liked) }, { errors++ })
        writer.toggle(video.id); runCurrent()
        video = video.copy(commentCount = 27, views = 99)
        response.complete(Result.failure(IllegalStateException())); runCurrent()
        assertFalse(video.likedByMe)
        assertEquals(4, video.likeCount)
        assertEquals(27, video.commentCount)
        assertEquals(99, video.views)
        assertEquals(1, errors)
    }

    @Test fun pendingChoiceSurvivesAConcurrentRead() = runTest {
        var video = original
        val response = CompletableDeferred<Result<Unit>>()
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, _ -> response.await() },
            { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        writer.toggle(video.id); runCurrent()
        val fresh = writer.reconcile(original.copy(likeCount = 10, commentCount = 2))
        assertTrue(fresh.likedByMe)
        assertEquals(11, fresh.likeCount)
        assertEquals(2, fresh.commentCount)
        writer.clear()
    }

    @Test fun oldResponseCannotEraseALikeAfterTheWriteHasFinished() = runTest {
        var video = original
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, _ -> Result.success(Unit) },
            { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        val read = writer.readVersion()
        writer.toggle(video.id); runCurrent()
        assertTrue(writer.reconcile(original, read).likedByMe)
        assertFalse(writer.reconcile(original, writer.readVersion()).likedByMe)
    }

    @Test fun completingANewerReadDoesNotExposeTheVideoToAnOlderResponse() = runTest {
        var video = original
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, _ -> Result.success(Unit) },
            { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        val oldRead = writer.readVersion()
        writer.toggle(video.id); runCurrent()
        assertTrue(writer.reconcile(video, writer.readVersion()).likedByMe)
        assertTrue(writer.reconcile(original, oldRead).likedByMe)
    }

    @Test fun aFailedUnlikeDoesNotGetRestoredByAnOlderRead() = runTest {
        var video = original.withVideoLike(true)
        val writer = VideoLikeWriter(backgroundScope, { "account" }, { video }, { _, _ -> Result.failure(Exception()) },
            { _, liked -> video = video.withVideoLike(liked) }, {})
        val read = writer.readVersion()
        writer.toggle(video.id); runCurrent()
        assertTrue(video.likedByMe)
        assertTrue(writer.reconcile(original, read).likedByMe)
    }

    @Test fun lateFailureDoesNotChangeAnotherAccount() = runTest {
        var video = original
        var account = "first"
        val response = CompletableDeferred<Result<Unit>>()
        val writer = VideoLikeWriter(backgroundScope, { account }, { video }, { _, _ -> response.await() },
            { _, liked -> video = video.withVideoLike(liked) }, { fail() })
        writer.toggle(video.id); runCurrent()
        account = "second"; video = original.copy(title = "another account")
        response.complete(Result.failure(IllegalStateException())); runCurrent()
        assertEquals("another account", video.title)
        assertFalse(video.likedByMe)
    }
}
