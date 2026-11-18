package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayControllerLifetimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>()
    private val client = mockk<BillingClient>(relaxed = true)
    private val builder = mockk<BillingClient.Builder>()
    private val setup = slot<BillingClientStateListener>()
    private val products = slot<ProductDetailsResponseListener>()
    private val owned = slot<PurchasesResponseListener>()
    private val errors = mutableListOf<String>()
    private val catalogs = mutableListOf<List<PlayCoinProduct>>()
    private val purchases = mutableListOf<String>()
    private var canceled = 0
    private lateinit var controller: GooglePlayBillingController
    private fun result(code: Int = BillingClient.BillingResponseCode.OK, debug: String = "") =
        BillingResult.newBuilder().setResponseCode(code).setDebugMessage(debug).build()
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        mockkStatic(BillingClient::class)
        every { context.applicationContext } returns context
        every { BillingClient.newBuilder(any()) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.enableAutoServiceReconnection() } returns builder
        every { builder.build() } returns client
        every { client.isReady } returns false
        every { client.startConnection(capture(setup)) } just Runs
        every { client.queryProductDetailsAsync(any(), capture(products)) } just Runs
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(owned)) } just Runs
        every { client.launchBillingFlow(any(), any()) } returns result()
        controller = GooglePlayBillingController(context,"a",setOf("coins"),catalogs::add,{},
            { _, token -> purchases += token },{}, { canceled++ },errors::add)
    }
    @After fun stop() {
        controller.close(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain()
        unmockkStatic(BillingClient::class); unmockkStatic(Log::class)
    }
    private fun connect() { controller.start(); dispatcher.scheduler.runCurrent(); setup.captured.onBillingSetupFinished(result()); dispatcher.scheduler.runCurrent() }
    private fun purchased() = mockk<Purchase> {
        every { this@mockk.products } returns listOf("coins")
        every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        every { purchaseToken } returns "fictional-token"
    }
    private fun catalog(): QueryProductDetailsResult = mockk {
        every { productDetailsList } returns emptyList()
        every { unfetchedProductList } returns emptyList()
    }
    @Test fun aClosedControllerCannotDeliverAPurchaseCallback() {
        controller.close(); controller.onPurchasesUpdated(result(), mutableListOf(purchased())); dispatcher.scheduler.runCurrent()
        assertTrue(purchases.isEmpty())
    }
    @Test fun aClosedControllerCannotPublishACancelCallback() {
        controller.close(); controller.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
        dispatcher.scheduler.runCurrent(); assertEquals(0,canceled)
    }
    @Test fun closingBeforeConnectionCompletesCannotRestartQueries() {
        controller.start(); dispatcher.scheduler.runCurrent(); controller.close()
        setup.captured.onBillingSetupFinished(result()); dispatcher.scheduler.runCurrent()
        verify(exactly=0) { client.queryProductDetailsAsync(any(),any()) }
    }
    @Test fun aClosedCatalogCallbackCannotWriteBackToTheScreen() {
        connect(); controller.close(); products.captured.onProductDetailsResponse(result(),catalog()); dispatcher.scheduler.runCurrent()
        assertTrue(catalogs.isEmpty())
    }
    @Test fun aClosedOwnedPurchaseCallbackCannotStartVerification() {
        connect(); controller.close(); owned.captured.onQueryPurchasesResponse(result(), mutableListOf(purchased())); dispatcher.scheduler.runCurrent()
        assertTrue(purchases.isEmpty())
    }
    @Test fun repeatedStartDoesNotCreateConcurrentConnections() {
        controller.start(); controller.start(); dispatcher.scheduler.runCurrent()
        verify(exactly=1) { client.startConnection(any()) }
    }
    @Test fun closeIsIdempotent() {
        controller.close(); controller.close(); verify(exactly=1) { client.endConnection() }
    }
    @Test fun connectionErrorsDoNotExposeSdkDebugMessages() {
        controller.start(); dispatcher.scheduler.runCurrent()
        setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.ERROR,"internal-host/secret.env detail")); dispatcher.scheduler.runCurrent()
        assertEquals(1,errors.size); assertFalse(errors.single().contains("secret.env"))
    }
    @Test fun purchaseErrorsDoNotExposeSdkDebugMessages() {
        controller.onPurchasesUpdated(result(BillingClient.BillingResponseCode.ERROR,"private-service debug traceback"),null)
        dispatcher.scheduler.runCurrent(); assertEquals(1,errors.size); assertFalse(errors.single().contains("private-service"))
    }
    @Test fun failedOwnedPurchaseQueriesAreNotSilentlyIgnored() {
        connect(); owned.captured.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), mutableListOf())
        dispatcher.scheduler.runCurrent(); assertEquals(1,errors.size)
    }
    @Test fun missingConnectionCallbackHasADeadline() = runTest(dispatcher) {
        controller.start(); runCurrent(); advanceTimeBy(15001); runCurrent()
        assertEquals(1,errors.size)
    }
    @Test fun missingCatalogCallbackHasADeadline() = runTest(dispatcher) {
        connect(); advanceTimeBy(15001); runCurrent(); assertEquals(1,errors.size)
    }
}
