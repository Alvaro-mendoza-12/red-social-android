package conectate.com.presentation.messages

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CollectionCreationSessionTest {
    private class Fixture(test: TestScope) {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val errors = mutableListOf<String>()
        val session = CollectionCreationSession(test.backgroundScope, guard.captureAccountLease { owner }, errors::add)
    }

    @Test fun aFailedImageSelectionRetainsThePreviouslyConfirmedIcon() = runTest {
        val f = Fixture(this)
        f.session.setImageBytes(byteArrayOf(1))
        f.session.prepareImage { throw java.io.IOException("unreadable") }; runCurrent()
        assertArrayEquals(byteArrayOf(1), f.session.selectedImageBytes.value)
        assertFalse(f.session.preparingImage.value)
        assertEquals(1, f.errors.size)
    }

    @Test fun anOlderImageCannotReplaceANewerChoiceEvenIfItsReaderIgnoresCancellation() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<ByteArray>()
        f.session.prepareImage { withContext(NonCancellable) { old.await() } }; runCurrent()
        f.session.prepareImage { byteArrayOf(2) }; runCurrent()
        old.complete(byteArrayOf(1)); runCurrent()
        assertArrayEquals(byteArrayOf(2), f.session.selectedImageBytes.value)
        assertFalse(f.session.preparingImage.value)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun aNewSelectionStaysBusyWhenAnOlderReadFinishes() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<ByteArray>()
        val latest = CompletableDeferred<ByteArray>()
        f.session.prepareImage { withContext(NonCancellable) { old.await() } }; runCurrent()
        f.session.prepareImage { latest.await() }; runCurrent()
        old.complete(byteArrayOf(1)); runCurrent()
        assertTrue(f.session.preparingImage.value)
        latest.complete(byteArrayOf(2)); runCurrent()
        assertFalse(f.session.preparingImage.value)
    }

    @Test fun switchingAccountsPermanentlyClosesTheFormAndDiscardsALateImage() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<ByteArray>()
        f.session.setImageBytes(byteArrayOf(1))
        f.session.prepareImage { withContext(NonCancellable) { old.await() } }; runCurrent()
        f.guard.invalidate { f.owner = "b" }
        assertFalse(f.session.isCurrent())
        f.session.sessionChanged()
        assertFalse(f.session.sessionCurrent.value)
        assertNull(f.session.selectedImageBytes.value)
        old.complete(byteArrayOf(3)); runCurrent()
        assertNull(f.session.selectedImageBytes.value)
        assertFalse(f.session.preparingImage.value)
        f.guard.invalidate { f.owner = "a" }; f.session.sessionChanged()
        assertFalse(f.session.isCurrent())
    }

    @Test fun anExplicitNewLoginClosesTheOldFormButTokenRenewalDoesNot() = runTest {
        val f = Fixture(this)
        f.guard.commitIfCurrent(f.guard.snapshot()) {}
        f.session.sessionChanged()
        assertTrue(f.session.isCurrent())
        f.guard.invalidate {}; f.session.sessionChanged()
        assertFalse(f.session.isCurrent())
    }

    @Test fun clearingAnImageInvalidatesAnOutstandingPicker() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<ByteArray>()
        f.session.prepareImage { withContext(NonCancellable) { old.await() } }; runCurrent()
        f.session.setImageBytes(null)
        old.complete(byteArrayOf(2)); runCurrent()
        assertNull(f.session.selectedImageBytes.value)
        assertFalse(f.session.preparingImage.value)
    }
}
