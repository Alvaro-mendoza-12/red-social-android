package conectate.com.presentation.auth

import conectate.com.data.localfallback.GuestComment
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestCommentsTest {
    @Test fun retryUsesTheSameContentAndDistinguishesFailureFromAnEmptyResponse() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val comments = GuestComments(backgroundScope) { kind, id ->
            requests += kind to id
            if (requests.size == 1) throw IOException("transport details")
            emptyList()
        }

        comments.open("posts", "one")
        val loading = comments.state.value as GuestCommentsState.Loading
        runCurrent()
        val failed = comments.state.value as GuestCommentsState.Failed
        assertSame(loading.visit, failed.visit)
        assertTrue(failed.message.startsWith("No pudimos cargar los comentarios"))

        comments.retry(failed.visit)
        comments.retry(failed.visit)
        runCurrent()
        val ready = comments.state.value as GuestCommentsState.Ready
        assertSame(loading.visit, ready.visit)
        assertTrue(ready.comments.isEmpty())
        assertEquals(listOf("posts" to "one", "posts" to "one"), requests)
    }

    @Test fun aSlowReadStopsLoadingAndCanBeRetried() = runTest {
        var reads = 0
        val comments = GuestComments(backgroundScope) { _, _ ->
            if (++reads == 1) awaitCancellation()
            listOf(GuestComment("recovered"))
        }

        comments.open("videos", "one")
        advanceTimeBy(12_001)
        val failed = comments.state.value as GuestCommentsState.Failed
        comments.retry(failed.visit)
        runCurrent()
        assertEquals(listOf("recovered"), (comments.state.value as GuestCommentsState.Ready).comments.map { it.id })
    }

    @Test fun aResponseFromAClosedVisitCannotOverwriteTheReopenedDialog() = runTest {
        val oldResponse = CompletableDeferred<List<GuestComment>>()
        var reads = 0
        val comments = GuestComments(backgroundScope) { _, _ ->
            if (++reads == 1) withContext(NonCancellable) { oldResponse.await() }
            else listOf(GuestComment("new"))
        }

        comments.open("posts", "same")
        val old = comments.state.value!!.visit
        try {
            runCurrent()
            comments.close(old)
            assertNull(comments.state.value)
            comments.open("posts", "same")
            runCurrent()
        } finally {
            oldResponse.complete(listOf(GuestComment("old")))
            runCurrent()
        }
        val current = comments.state.value as GuestCommentsState.Ready
        assertNotSame(old, current.visit)
        assertEquals(listOf("new"), current.comments.map { it.id })
        comments.close(old)
        assertSame(current, comments.state.value)
    }

    @Test fun aRetryFromAnotherVisitDoesNotReloadTheCurrentContent() = runTest {
        var reads = 0
        val comments = GuestComments(backgroundScope) { _, _ ->
            reads++
            throw IllegalStateException("Este contenido ya no está disponible o es privado.")
        }

        comments.open("posts", "same")
        runCurrent()
        val old = comments.state.value!!.visit
        comments.open("videos", "same")
        runCurrent()
        val current = comments.state.value as GuestCommentsState.Failed
        comments.retry(old)
        runCurrent()
        assertSame(current, comments.state.value)
        assertEquals("videos", current.visit.kind)
        assertEquals("Este contenido ya no está disponible o es privado.", current.message)
        assertEquals(2, reads)
    }

    @Test fun repeatedIdsDoNotCreateDuplicateRowsAndTheListStaysBounded() = runTest {
        val expected = (1..100).map { GuestComment("row-$it") }
        val comments = GuestComments(backgroundScope) { _, _ ->
            expected.take(8) + expected + GuestComment("overflow")
        }

        comments.open("posts", "one")
        runCurrent()
        assertEquals(expected, (comments.state.value as GuestCommentsState.Ready).comments)
    }
}
