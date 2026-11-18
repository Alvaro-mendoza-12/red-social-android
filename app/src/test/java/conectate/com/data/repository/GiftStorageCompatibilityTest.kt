package conectate.com.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The legacy no-op rule matches AOSP API24/25; memory and durable snapshots are separate. */
class GiftStorageCompatibilityTest {
    private lateinit var f: GiftHttpFixture
    @Before fun start() { f=GiftHttpFixture() }
    @After fun stop() { f.close() }
    @Test fun aLegacyNoopCommitCannotAuthorizeAnUndurableRetry()=runBlocking {
        f.legacyNoopCommit=true;f.failedWrites=2
        assertTrue(f.send().isFailure)
        f.send("another-ui-key")
        assertTrue("No charge may leave until a real disk write succeeds", f.requests.isEmpty())
        assertTrue(f.persisted.filterKeys { !it.startsWith("__") }.isEmpty())
    }
    @Test fun failedCleanupCannotCreateASecondChargeOnRetry()=runBlocking {
        f.failRemovalOnce=true
        assertTrue(f.send().isFailure)
        assertTrue(f.send("another-ui-key").isSuccess)
        assertEquals("The acknowledged first charge must be replayed",1,f.charges.get())
    }
    @Test fun evenAFailedRestoreKeepsTheOriginalKeyInMemoryForReconfirmation()=runBlocking {
        f.failRemovalOnce=true;f.failRestoreOnce=true
        assertTrue(f.send().isFailure)
        assertEquals("first-key",f.disk.values.singleOrNull())
        assertTrue(f.send("another-ui-key").isSuccess);assertEquals(1,f.charges.get())
    }
    @Test fun recoveredStoragePersistsTheSameIntentBeforeSending()=runBlocking {
        f.legacyNoopCommit=true;f.failedWrites=2
        assertTrue(f.send().isFailure);assertTrue(f.send("second").isFailure)
        assertTrue(f.send("third").isSuccess);assertEquals(1,f.charges.get())
        assertTrue(f.persisted.filterKeys { !it.startsWith("__") }.isEmpty())
    }
}
