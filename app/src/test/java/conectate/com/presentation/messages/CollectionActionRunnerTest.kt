package conectate.com.presentation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionActionRunnerTest {
    @Test fun aDoubleTapExecutesOneCommandAndOnlyConfirmedSuccessNavigates() = runTest {
        val completed = CompletableDeferred<Result<Unit>>()
        var requests = 0; var navigations = 0
        val errors = mutableListOf<String>()
        val runner = CollectionActionRunner(backgroundScope, { "owner" }, errors::add)
        repeat(2) { runner.run("group", "No se pudo salir", { true }, { requests++; completed.await() }, { navigations++ }) }
        runCurrent(); assertEquals(1, requests); assertTrue(runner.busy.value.isNotEmpty())
        completed.complete(Result.failure(Exception("network"))); runCurrent()
        assertEquals(0, navigations); assertEquals(listOf("No se pudo salir"), errors); assertTrue(runner.busy.value.isEmpty())
        runner.run("group", "No se pudo salir", { true }, { Result.success(Unit) }, { navigations++ }); runCurrent()
        assertEquals(1, navigations)
    }
    @Test fun aLateResultCannotNavigateOrPublishIntoAnotherAccountOrCollection() = runTest {
        var owner = "a"; var current = true; var callbacks = 0
        val completed = CompletableDeferred<Result<Unit>>()
        val runner = CollectionActionRunner(backgroundScope, { owner }, { callbacks++ })
        runner.run("room", "Error", { current }, { completed.await() }, { callbacks++ }); runCurrent()
        owner = "b"; current = false; completed.complete(Result.success(Unit)); runCurrent()
        assertEquals(0, callbacks); assertTrue(runner.busy.value.isEmpty())
    }
    @Test fun exceptionsAreVisibleButCancellationIsNeverConvertedIntoAnActionFailure() = runTest {
        val errors = mutableListOf<String>()
        val runner = CollectionActionRunner(backgroundScope, { "a" }, errors::add)
        runner.run<Unit>("room", "No se pudo guardar", { true }, { throw IllegalStateException("failure") }); runCurrent()
        runner.run<Unit>("room", "Cancelled", { true }, { Result.failure(CancellationException("left")) }); runCurrent()
        assertEquals(listOf("No se pudo guardar"), errors); assertTrue(runner.busy.value.isEmpty())
    }
}
