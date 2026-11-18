package conectate.com.data.repository

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WalletBalanceOrderingTest {
    private lateinit var f: GiftHttpFixture
    @Before fun start() { f = GiftHttpFixture() }
    @After fun stop() { f.close() }

    @Test fun anOldBalanceReplyCannotReplaceAConfirmedGiftBalance() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.onBalance={entered.complete(Unit);release.await()}
        val old=async { f.repository.fetchBalance() }
        try {
            withTimeout(5000) { entered.await() }
            assertEquals(990,f.send().getOrThrow().senderBalanceDiamonds)
            release.complete(Unit)
            val result=old.await()
            assertEquals(990,f.repository.coinBalance.value)
            assertTrue("A superseded read cannot confirm freshness",result.isFailure)
        } finally { release.complete(Unit);old.cancelAndJoin() }
    }

    @Test fun aNewerBalanceReadKeepsItsResultWhenTheEarlierReplyArrivesLast() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        f.onBalance={if(calls.incrementAndGet()==1) {entered.complete(Unit);release.await()}}
        val old=async { f.repository.fetchBalance() }
        try {
            withTimeout(5000) { entered.await() };f.walletBalance=700
            assertTrue(f.repository.fetchBalance().isSuccess);assertEquals(700,f.repository.coinBalance.value)
            release.complete(Unit);val result=old.await();assertEquals(700,f.repository.coinBalance.value);assertTrue(result.isFailure)
        } finally { release.complete(Unit);old.cancelAndJoin() }
    }

    @Test fun anOldReadCannotBecomeFreshAfterItsReplacementFailed() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        f.onBalance={if(calls.incrementAndGet()==1) {entered.complete(Unit);release.await()}}
        val old=async { f.repository.fetchBalance() }
        try {
            withTimeout(5000) { entered.await() };f.walletBody="{}"
            assertTrue(f.repository.fetchBalance().isFailure)
            release.complete(Unit);val result=old.await();assertEquals(0,f.repository.coinBalance.value);assertTrue(result.isFailure)
        } finally { release.complete(Unit);old.cancelAndJoin() }
    }

    @Test fun anOldBalanceReplyCannotReplaceAVerifiedPurchase() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.onBalance={entered.complete(Unit);release.await()}
        val old=async { f.repository.fetchBalance() }
        try {
            withTimeout(5000) { entered.await() }
            assertTrue(f.repository.verifyGooglePlayPurchase("coins-100","fixture-token",f.repository.purchaseSession()).isSuccess)
            assertEquals(1100,f.repository.coinBalance.value)
            release.complete(Unit);val result=old.await();assertEquals(1100,f.repository.coinBalance.value);assertTrue(result.isFailure)
        } finally { release.complete(Unit);old.cancelAndJoin() }
    }

    @Test fun anAmbiguousGiftReplyCannotMakeAnEarlierReadFresh() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.onBalance={entered.complete(Unit);release.await()}
        val old=async { f.repository.fetchBalance() }
        try {
            withTimeout(5000) { entered.await() };f.overrideReceipt="{}"
            assertTrue(f.send().isFailure);assertTrue(f.disk.isNotEmpty())
            release.complete(Unit);val result=old.await();assertEquals(0,f.repository.coinBalance.value);assertTrue(result.isFailure)
        } finally { release.complete(Unit);old.cancelAndJoin() }
    }

    @Test fun aCurrentUncontestedReadStillPublishesItsConfirmedBalance() = runBlocking {
        assertTrue(f.repository.fetchBalance().isSuccess)
        assertEquals(1000,f.repository.coinBalance.value)
    }

    @Test fun aReadStartedAfterAConfirmedGiftCanStillRefreshTheBalance() = runBlocking {
        assertTrue(f.send().isSuccess);f.walletBalance=990
        assertTrue(f.repository.fetchBalance().isSuccess)
        assertEquals(990,f.repository.coinBalance.value)
    }

    @Test fun cancellingAnOlderReadCannotInvalidateItsReplacement() = runBlocking {
        val entered1=CompletableDeferred<Unit>();val entered2=CompletableDeferred<Unit>()
        val release1=CompletableDeferred<Unit>();val release2=CompletableDeferred<Unit>();val calls=AtomicInteger()
        f.onBalance={if(calls.incrementAndGet()==1) {entered1.complete(Unit);release1.await()} else {entered2.complete(Unit);release2.await()}}
        val old=async { f.repository.fetchBalance() };var next: Deferred<Result<Unit>>?=null
        try {
            withTimeout(5000) { entered1.await() };f.walletBalance=700
            next=async { f.repository.fetchBalance() };withTimeout(5000) { entered2.await() }
            old.cancelAndJoin();release2.complete(Unit)
            assertTrue(next.await().isSuccess);assertEquals(700,f.repository.coinBalance.value)
        } finally { release1.complete(Unit);release2.complete(Unit);old.cancelAndJoin();next?.cancelAndJoin() }
    }

    @Test fun aReadStartedDuringAnAmbiguousGiftCannotConfirmAnOlderSnapshot() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.onBalance={entered.complete(Unit);release.await()};f.holdReply=CompletableDeferred();f.overrideReceipt="{}"
        val gift=async { f.send() };var read: Deferred<Result<Unit>>?=null
        try {
            withTimeout(5000) { f.entered.await() }
            read=async { f.repository.fetchBalance() };withTimeout(5000) { entered.await() }
            f.holdReply!!.complete(Unit);assertTrue(gift.await().isFailure)
            release.complete(Unit);val result=read.await()
            assertEquals(0,f.repository.coinBalance.value);assertTrue(result.isFailure)
        } finally { f.holdReply!!.complete(Unit);release.complete(Unit);gift.cancelAndJoin();read?.cancelAndJoin() }
    }

    @Test fun cancellingAGiftInvalidatesAReadStartedWhileItWasInFlight() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.onBalance={entered.complete(Unit);release.await()};f.holdReply=CompletableDeferred()
        val gift=async { f.send() };var read: Deferred<Result<Unit>>?=null
        try {
            withTimeout(5000) { f.entered.await() }
            read=async { f.repository.fetchBalance() };withTimeout(5000) { entered.await() }
            gift.cancelAndJoin();release.complete(Unit);val result=read.await()
            assertEquals(0,f.repository.coinBalance.value);assertTrue(result.isFailure);assertTrue(f.disk.isNotEmpty())
        } finally { f.holdReply!!.complete(Unit);release.complete(Unit);gift.cancelAndJoin();read?.cancelAndJoin() }
    }
}
