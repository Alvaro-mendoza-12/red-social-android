package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import conectate.com.data.model.*
import conectate.com.data.supabase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class GiftHttpFixture {
    @Volatile var account: String? = "sender"
    val guard = AuthSessionMutationGuard()
    val auth = mockk<AuthService>()
    val config = mockk<SupabaseConfig>()
    private val memory = ConcurrentHashMap<String, String>()
    val disk: Map<String, String> get() = memory.filterKeys { !it.startsWith("__") }
    val persisted = ConcurrentHashMap<String, String>()
    var legacyNoopCommit = false
    var failRemovalOnce = false
    var failRestoreOnce = false
    private var removalFailed = false
    private val edits = ThreadLocal.withInitial { mutableMapOf<String, String?>() }
    var failedWrites = 0
    var onWrite: (() -> Unit)? = null
    var onToken: (suspend () -> Unit)? = null
    var onRenew: (suspend () -> Unit)? = null
    var onCatalog: (() -> Unit)? = null
    var onReply: (() -> Unit)? = null
    var holdReply: CompletableDeferred<Unit>? = null
    var entered = CompletableDeferred<Unit>()
    var status = HttpStatusCode.OK
    var overrideReceipt: String? = null
    var catalogPrice = 10
    val endpoints = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<JsonObject>()
    val charges = AtomicInteger()
    private val ledger = ConcurrentHashMap<String, String>()
    val json = Json { ignoreUnknownKeys = true }
    val gift = VirtualGift("gift", "Regalo QA", priceDiamonds = 10)
    val client = HttpClient(MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("/send_virtual_gift") || request.url.encodedPath.endsWith("/send_virtual_gift_v2") -> {
                endpoints += request.url.encodedPath
                val payload = json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().toString(Charsets.UTF_8)).jsonObject
                requests += payload
                val key = payload.getValue("p_idempotency_key").jsonPrimitive.content
                val replay = ledger.containsKey(key)
                val receipt = ledger.getOrPut(key) {
                    val number = charges.incrementAndGet()
                    buildJsonObject {
                        put("ok", true); put("transaction_id", "receipt-$number")
                        put("amount_diamonds", payload["p_expected_price_diamonds"] ?: JsonPrimitive(10))
                        put("sender_id", payload["p_sender_id"]!!); put("receiver_id", payload["p_receiver_id"]!!)
                        put("gift_id", payload["p_gift_id"]!!); put("live_stream_id", payload["p_live_stream_id"]!!)
                        put("is_public", payload["p_is_public"]!!); put("sender_balance_diamonds", 990)
                    }.toString()
                }
                entered.complete(Unit); holdReply?.await(); onReply?.invoke()
                val replayReceipt = JsonObject(json.parseToJsonElement(receipt).jsonObject + ("idempotent_replay" to JsonPrimitive(replay))).toString()
                return@MockEngine respond(overrideReceipt ?: replayReceipt, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            request.url.encodedPath.endsWith("/virtual_gifts") -> {
                onCatalog?.invoke()
                """[{"id":"gift","name":"Regalo QA","price_diamonds":$catalogPrice}]"""
            }
            request.url.encodedPath.endsWith("/wallet_get_or_create") ->
                """{"user_id":"$account","balance_diamonds":1000,"balance_debt_diamonds":0}"""
            request.url.encodedPath.endsWith("/billing_capabilities") -> """{"enabled":false,"products":[]}"""
            else -> "[]"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(json) } }
    val repository: WalletRepository

    init {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { config.httpClient } returns client
        every { config.isConfigured } returns true
        every { config.supabaseUrl } returns "https://fixture.invalid"
        every { config.supabaseAnonKey } returns "qa-anon"
        every { config.json } returns json
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        every { auth.getCurrentUserId() } answers { account }
        every { auth.getAccessToken() } answers { "token-$account" }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { auth.ensureValidAccessToken() } coAnswers { onRenew?.invoke(); Result.success("token-$account") }
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers {
            val token = "token-${firstArg<String>()}"; onToken?.invoke(); token
        }
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getString(any(), any()) } answers { memory[firstArg()] }
        every { preferences.edit() } answers { edits.get().clear(); editor }
        every { editor.putString(any(), any()) } answers { edits.get()[firstArg()] = secondArg(); editor }
        every { editor.remove(any()) } answers { edits.get()[firstArg()] = null; editor }
        every { editor.commit() } answers {
            val update = requireNotNull(edits.get())
            val changed = update.any { (key, value) -> memory[key] != value }
            update.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
            onWrite?.invoke()
            if (legacyNoopCommit && !changed) true
            else if (failedWrites > 0) { failedWrites--; false }
            else if (failRemovalOnce && update.any { !it.key.startsWith("__") && it.value == null }) {
                failRemovalOnce = false; removalFailed = true; false
            } else if (removalFailed && failRestoreOnce && update.any { !it.key.startsWith("__") && it.value != null }) {
                failRestoreOnce = false; false
            } else {
                persisted.clear(); persisted.putAll(memory); true
            }
        }
        repository = WalletRepository(config, auth, context)
    }
    fun switchTo(value: String?) { guard.invalidate { account = value } }
    fun roundTrip() { switchTo("other"); switchTo("sender") }
    suspend fun send(key: String = "first-key") = repository.sendGift("receiver", gift.id, "live", true, key)
    suspend fun expectExpired(action: suspend () -> Unit) {
        try { action(); fail("An expired visit cannot confirm or send this gift") } catch (_: CancellationException) { }
    }
    fun close() { client.close(); unmockkStatic(Log::class) }
}

class WalletGiftSessionTest {
    private lateinit var f: GiftHttpFixture
    @Before fun start() { f = GiftHttpFixture() }
    @After fun stop() { f.close() }

    @Test fun theCurrentAccountCanSendAConfirmedGift() = runBlocking {
        val receipt = f.send().getOrThrow()
        assertEquals("receipt-1", receipt.transactionId); assertEquals(990, f.repository.coinBalance.value)
        assertEquals(1, f.charges.get()); assertTrue(f.disk.isEmpty())
    }
    @Test fun changingAccountDuringTokenLookupCannotSendTheGift() = runBlocking {
        f.onToken = { f.switchTo("other") }
        f.expectExpired { f.send() }; assertTrue(f.requests.isEmpty())
    }
    @Test fun returningToTheAccountDuringTokenLookupStillInvalidatesTheGift() = runBlocking {
        f.onToken = { f.roundTrip() }
        f.expectExpired { f.send() }; assertTrue(f.requests.isEmpty())
    }
    @Test fun renewingCredentialsCannotTransferTheGiftToAnotherVisit() = runBlocking {
        f.onRenew = { f.roundTrip() }
        f.expectExpired { f.send() }; assertTrue(f.requests.isEmpty())
    }
    @Test fun aChangedVisitDuringCatalogLookupCannotSendTheGift() = runBlocking {
        f.onCatalog = { f.roundTrip() }
        f.expectExpired { f.send() }; assertTrue(f.requests.isEmpty())
    }
    @Test fun aChangedVisitDuringDurableReservationCannotSendTheGift() = runBlocking {
        f.onWrite = { f.roundTrip() }
        f.expectExpired { f.send() }; assertTrue(f.requests.isEmpty())
    }
    @Test fun aReplyForAnExpiredVisitKeepsTheRetryKeyAndCannotConfirmTheGift() = runBlocking {
        f.onReply = { f.roundTrip() }
        f.expectExpired { f.send() }; assertEquals("first-key", f.disk.values.single())
    }
    @Test fun aLostReplyAndARetryDoNotDuplicateTheCharge() = runBlocking {
        f.status = HttpStatusCode.ServiceUnavailable
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
        f.status = HttpStatusCode.OK
        assertTrue(f.send("different-ui-key").isSuccess)
        assertEquals(1, f.charges.get())
        assertEquals(listOf("first-key", "first-key"), f.requests.map { it.getValue("p_idempotency_key").jsonPrimitive.content })
    }
    @Test fun aKeyOnlyPresentInMemoryMustBePersistedAgainBeforeRetrying() = runBlocking {
        f.failedWrites = 2
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
        assertTrue("A retry must confirm durability again", f.send("replacement").isFailure)
        assertTrue(f.requests.isEmpty())
    }
    @Test fun anAcknowledgementForAnotherReceiverCannotConfirmThisGift() = runBlocking {
        f.overrideReceipt = """{"transaction_id":"wrong","sender_balance_diamonds":990,"receiver_id":"someone-else"}"""
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
    }
    @Test fun anInvalidNegativeBalanceCannotConfirmThisGift() = runBlocking {
        f.overrideReceipt = """{"transaction_id":"wrong","sender_balance_diamonds":-1}"""
        assertTrue(f.send().isFailure); assertEquals("first-key", f.disk.values.single())
    }
    @Test fun aVersionedReceiptRequiresTheChargedAmountAndReplayFlag() = runBlocking {
        f.overrideReceipt = """{"transaction_id":"incomplete","sender_balance_diamonds":990}"""
        assertTrue(f.send().isFailure)
        assertEquals("first-key", f.disk.values.single())
    }
    @Test fun suppliedReceiptDetailsMustAcknowledgeTheSameIntent() = runBlocking {
        for (field in listOf("\"gift_id\":\"another-gift\"", "\"live_stream_id\":\"another-live\"", "\"is_public\":false", "\"ok\":false")) {
            f.overrideReceipt = "{\"transaction_id\":\"wrong\",\"sender_balance_diamonds\":990,$field}"
            assertTrue("Unexpected acknowledgement: $field", f.send().isFailure)
            assertEquals("first-key", f.disk.values.single())
        }
    }
    @Test fun pendingLookupUsesTheSameNormalizedAccountAsTheSend() = runBlocking {
        f.status = HttpStatusCode.ServiceUnavailable
        assertTrue(f.send().isFailure)
        f.account = "SENDER"
        assertTrue(f.repository.hasPendingGift("receiver", "live", "gift", true))
    }
}
