package conectate.com.presentation.profile

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileContentSubscriptionsTest {
    @Test fun replacingATabCancelsItsPreviousObserverWithoutDiscardingAnotherTab() = runTest {
        val reads = ProfileReadScope { "owner" }; reads.begin("profile")
        val loader = ProfileContentSubscriptions(backgroundScope, reads)
        var observers = 0; var errors = 0
        val source = flow<List<String>> { observers++; try { awaitCancellation() } finally { observers-- } }
        loader.observe("posts", "profile", { source }, {}, { errors++ }); runCurrent()
        loader.observe("videos", "profile", { source }, {}, { errors++ }); runCurrent()
        loader.observe("posts", "profile", { source }, {}, { errors++ }); runCurrent()
        assertEquals(2, observers); assertEquals(0, errors)
        loader.cancelAll(); runCurrent(); assertEquals(0, observers)
    }
    @Test fun lateValuesFromAPreviousProfileOrAccountCannotReplaceCurrentContent() = runTest {
        var account = "one"
        val reads = ProfileReadScope { account }; reads.begin("profile")
        val loader = ProfileContentSubscriptions(backgroundScope, reads)
        val source = MutableSharedFlow<Int>(); val values = mutableListOf<Int>()
        loader.observe("posts", "profile", { source }, values::add, {}); runCurrent()
        source.emit(1); runCurrent()
        reads.begin("another"); source.emit(2); runCurrent()
        account = "two"; source.emit(3); runCurrent()
        assertEquals(listOf(1), values)
    }
}
