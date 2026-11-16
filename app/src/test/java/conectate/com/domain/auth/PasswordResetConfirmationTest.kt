package conectate.com.domain.auth

import conectate.com.data.supabase.AuthSessionMutationGuard
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PasswordResetConfirmationTest {
    @Test fun aReceiptSurvivesItsVisitWithoutAuthorizingCompletion() {
        val grant = UUID.randomUUID().toString()
        var current = true
        ConfirmedPasswordResets.record(grant, false) { action -> if (current) { action(); true } else false }
        val receipt = ConfirmedPasswordResets.confirmation(grant)!!
        assertTrue(receipt.mayPublishCompletion())
        current = false
        assertFalse(receipt.mayPublishCompletion())
        assertNotNull(ConfirmedPasswordResets.confirmation(grant))
    }

    @Test fun onlyTheAcknowledgedGrantIsSuppressedAndWhitespaceIsNormalized() {
        val grant = UUID.randomUUID().toString()
        ConfirmedPasswordResets.record(" $grant ", true) { action -> action(); true }
        assertTrue(ConfirmedPasswordResets.confirmation(grant)!!.requiresTwoFactor)
        assertNull(ConfirmedPasswordResets.confirmation(grant + "-other"))
        assertNull(ConfirmedPasswordResets.confirmation(""))
    }

    @Test fun theRegistryHasABoundedProcessLifetimeRatherThanDurableSingleUse() {
        val prefix = UUID.randomUUID().toString()
        repeat(65) { ConfirmedPasswordResets.record("$prefix-$it", false) { action -> action(); true } }
        assertNull(ConfirmedPasswordResets.confirmation("$prefix-0"))
        assertNotNull(ConfirmedPasswordResets.confirmation("$prefix-1"))
        assertNotNull(ConfirmedPasswordResets.confirmation("$prefix-64"))
    }

    @Test fun ownActivationCanReplaceTheVisitPredicateWithoutRetainingTheOldOne() {
        val grant = UUID.randomUUID().toString()
        ConfirmedPasswordResets.record(grant, false) { _ -> false }
        ConfirmedPasswordResets.record(grant, false) { action -> action(); true }
        assertTrue(ConfirmedPasswordResets.confirmation(grant)!!.mayPublishCompletion())
    }

    @Test fun accountChangeCannotInterleaveWithTheFinalCompletionPublication() {
        val grant = UUID.randomUUID().toString()
        val guard = AuthSessionMutationGuard()
        val generation = guard.snapshot()
        val displayed = AtomicReference("A")
        ConfirmedPasswordResets.record(grant, false) { action -> guard.commitIfCurrent(generation, mutation = action) }
        val receipt = ConfirmedPasswordResets.confirmation(grant)!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changeAttempted = CountDownLatch(1)
        val changed = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val publication = workers.submit<Boolean> {
                receipt.publishIfCurrent {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    displayed.set("A completion")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = workers.submit {
                changeAttempted.countDown()
                guard.invalidate { displayed.set("B"); changed.countDown() }
            }
            assertTrue(changeAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("Replacement must serialize with the admitted completion", changed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(publication.get(5, TimeUnit.SECONDS))
            replacement.get(5, TimeUnit.SECONDS)
            assertEquals("B", displayed.get())
            assertFalse(receipt.mayPublishCompletion())
            assertFalse(receipt.publishIfCurrent { displayed.set("stale A") })
            assertEquals("B", displayed.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }
}
