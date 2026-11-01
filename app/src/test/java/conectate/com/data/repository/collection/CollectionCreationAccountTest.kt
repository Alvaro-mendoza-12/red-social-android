package conectate.com.data.repository.collection

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CollectionCreationAccountTest {
    @Test fun aCreationUsesTheCapturedOwnersTokenAndExplicitIdentity() = runTest {
        val guard = AuthSessionMutationGuard()
        var requested: String? = null
        val result = withCollectionCreationAccount(guard.captureAccountLease { "a" }, "a", { requested = it; "a-token" }) { owner, token, check ->
            check(); assertEquals("a", owner); assertEquals("a-token", token)
            Result.success("room")
        }
        assertEquals("a", requested)
        assertEquals("room", result.getOrThrow())
    }

    @Test fun anOldFormCannotAcquireTheNewOwnersCredentials() = runTest {
        val guard = AuthSessionMutationGuard()
        var requested = false
        val error = runCatching {
            withCollectionCreationAccount(guard.captureAccountLease { "b" }, "a", { requested = true; "b-token" }) { _, _, _ -> Result.success(Unit) }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(requested)
    }

    @Test fun anAwaitedTokenFromAnObsoleteLoginCannotCreateARoom() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        var created = false
        val lease = guard.captureAccountLease { owner }
        val task = async {
            withCollectionCreationAccount(lease, "a", { started.complete(Unit); reply.await() }) { _, _, _ -> created = true; Result.success(Unit) }
        }
        started.await(); guard.invalidate { owner = "b" }; reply.complete("old-token")
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertFalse(created)
    }

    @Test fun aSwitchWhilePreparingTheIconCannotProceedToTheCreateRequest() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val started = CompletableDeferred<Unit>()
        val icon = CompletableDeferred<Unit>()
        var posted = false
        val lease = guard.captureAccountLease { owner }
        val task = async {
            withCollectionCreationAccount(lease, "a", { "a-token" }) { _, _, check ->
                started.complete(Unit); icon.await(); check()
                posted = true; Result.success("room")
            }
        }
        started.await(); guard.invalidate { owner = "b" }; icon.complete(Unit)
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertFalse(posted)
    }

    @Test fun anAcknowledgementCannotReachANewLoginOfTheSameIdentity() = runTest {
        val guard = AuthSessionMutationGuard()
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<String>>()
        val lease = guard.captureAccountLease { "a" }
        val task = async {
            withCollectionCreationAccount(lease, "a", { "token" }) { _, _, _ -> started.complete(Unit); reply.await() }
        }
        started.await(); guard.invalidate {}; reply.complete(Result.success("old-room"))
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun aCurrentAccountKeepsTheImageFailureInsteadOfClaimingCreation() = runTest {
        val guard = AuthSessionMutationGuard()
        val failure = java.io.IOException("upload failed")
        val result = withCollectionCreationAccount(guard.captureAccountLease { "a" }, "a", { "token" }) { _, _, _ ->
            Result.failure<String>(failure)
        }
        assertSame(failure, result.exceptionOrNull())
    }
}
