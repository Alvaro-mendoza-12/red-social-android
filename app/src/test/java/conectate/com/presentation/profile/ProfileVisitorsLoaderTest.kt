package conectate.com.presentation.profile

import conectate.com.data.model.User
import conectate.com.data.repository.ProfileViewer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileVisitorsLoaderTest {
    private val visitor = ProfileViewer(User(id = "visitor", display_name = "Visitante"), "2026-10-03T12:00:00Z")

    @Test fun pendingReadDoesNotLookLikeAnEmptyResult() = runTest {
        val pending = CompletableDeferred<Result<List<ProfileViewer>>>()
        val loader = ProfileVisitorsLoader(backgroundScope, { "owner" }, { pending.await() })
        loader.refresh(true); runCurrent()
        assertTrue(loader.state.value.loading)
        pending.complete(Result.success(emptyList())); runCurrent()
        assertFalse(loader.state.value.loading)
        assertNull(loader.state.value.error)
    }
    @Test fun failedReadCanBeRetried() = runTest {
        var fail = true
        val loader = ProfileVisitorsLoader(backgroundScope, { "owner" }, {
            if (fail) Result.failure(IllegalStateException()) else Result.success(listOf(visitor))
        })
        loader.refresh(true); runCurrent(); assertNotNull(loader.state.value.error)
        fail = false; loader.refresh(true); runCurrent()
        assertEquals(listOf(visitor), loader.state.value.items)
        assertNull(loader.state.value.error)
    }
    @Test fun privacyOptOutCancelsAndClearsThePreviousList() = runTest {
        var calls = 0
        val loader = ProfileVisitorsLoader(backgroundScope, { "owner" }, { calls++; Result.success(listOf(visitor)) })
        loader.refresh(true); runCurrent(); loader.refresh(false); runCurrent()
        assertFalse(loader.state.value.enabled)
        assertTrue(loader.state.value.items.isEmpty())
        assertEquals(1, calls)
    }
    @Test fun accountSwitchRejectsAnOldResultAndClearsPreviousItems() = runTest {
        var actor = "first"
        val pending = CompletableDeferred<Result<List<ProfileViewer>>>()
        var completed = true
        val loader = ProfileVisitorsLoader(backgroundScope, { actor }, {
            if (completed) Result.success(listOf(visitor)) else pending.await()
        })
        loader.refresh(true); runCurrent(); completed = false
        loader.refresh(true); runCurrent(); actor = "second"
        pending.complete(Result.success(listOf(visitor.copy(user = User(id = "late")))))
        runCurrent()
        assertTrue(loader.state.value.items.isEmpty())
        loader.refresh(true)
        assertTrue(loader.state.value.items.isEmpty())
    }
    @Test fun sessionChangeClearsPrivateVisitorsBeforeAnotherRead() = runTest {
        var actor: String? = "first"
        val loader = ProfileVisitorsLoader(backgroundScope, { actor }, { Result.success(listOf(visitor)) })
        loader.refresh(true); runCurrent(); assertEquals(listOf(visitor), loader.state.value.items)
        actor = null; loader.accountChanged()
        assertTrue(loader.state.value.items.isEmpty())
        assertFalse(loader.state.value.loading)
    }
    @Test fun timeoutIsShownAsAnErrorWithARetryInsteadOfAnEmptyResult() = runTest {
        val loader = ProfileVisitorsLoader(backgroundScope, { "owner" }, { awaitCancellation() }, timeoutMs = 100)
        loader.refresh(true); runCurrent(); advanceTimeBy(100); runCurrent()
        assertFalse(loader.state.value.loading)
        assertNotNull(loader.state.value.error)
    }
    @Test fun refreshingKeepsLoadedVisitorsUntilTheResponseArrives() = runTest {
        var pending = false
        val loader = ProfileVisitorsLoader(backgroundScope, { "owner" }, {
            if (pending) awaitCancellation() else Result.success(listOf(visitor))
        })
        loader.refresh(true); runCurrent(); pending = true
        loader.refresh(true); runCurrent()
        assertTrue(loader.state.value.loading)
        assertEquals(listOf(visitor), loader.state.value.items)
    }
}
