package conectate.com.data.localfallback

import conectate.com.data.model.SharedMessageDestination
import conectate.com.data.model.SharedMessageReference
import conectate.com.data.model.SharedMessageUnavailable
import conectate.com.data.repository.SharedMessageRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.test.UnitTestApplication
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class SharedPostTransportTest {
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private val parser = Json { ignoreUnknownKeys = true }
    private val calls = mutableListOf<Triple<String, String, String?>>()
    private val postBodies = mutableListOf<String>()
    private var reply = HttpStatusCode.OK
    private var body = """{"version":1,"kinds":["post"]}"""
    private var onReply: () -> Unit = {}
    private val client = HttpClient(MockEngine { req ->
        calls += Triple(req.method.value, req.url.encodedPath, req.headers[HttpHeaders.Authorization])
        if (req.method.value == "POST") postBodies += (req.body as OutgoingContent.ByteArrayContent).bytes().toString(Charsets.UTF_8)
        onReply()
        respond(body, reply, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(parser) } }
    private lateinit var repo: SharedMessageRepository
    private val scopeId = "group-1"
    private val messageId = "83d047fb-e4ac-3ea5-b3ba-0028343d42ab"
    private val post = SharedMessageReference.post("Post-Abc123")

    @Before fun setup() {
        mockkObject(RuntimeBackendEndpoint)
        every { RuntimeBackendEndpoint.baseUrl } returns "https://fixture.invalid"
        every { RuntimeBackendEndpoint.isConfigured } returns true
        coEvery { RuntimeBackendEndpoint.ensureFreshEndpoint() } returns "https://fixture.invalid"
        every { config.httpClient } returns client
        every { config.json } returns parser
        every { auth.isLocalFallbackSession() } returns true
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { "owner" } }
        coEvery { auth.getAccessTokenForAccountAwait("owner") } returns "fixture-token"
        repo = SharedMessageRepository(LocalFallbackApiClient(config), auth)
    }

    @After fun stop() { client.close(); unmockkObject(RuntimeBackendEndpoint) }

    @Test fun onlyTheKnownLegacy404AllowsAnUntypedPlan() = runBlocking {
        val lease = repo.captureAccount()
        assertTrue(repo.supportsPostShares(lease))
        reply = HttpStatusCode.NotFound
        body = """{"error":"NOT_FOUND","path":"/v1/data/messages/shared-content/capabilities"}"""
        assertFalse(repo.supportsPostShares(lease))
        body = """{"error":"NOT_FOUND","path":"/other"}"""
        assertTrue(runCatching { repo.supportsPostShares(lease) }.isFailure)
        reply = HttpStatusCode.ServiceUnavailable
        assertTrue(runCatching { repo.supportsPostShares(lease) }.isFailure)
        assertTrue(calls.all { it.first == "GET" && it.third == "Bearer fixture-token" })
    }

    @Test fun anOrdinaryMessageReceiptCannotAcknowledgeATypedSend() = runBlocking {
        val lease = repo.captureAccount()
        body = """{"message":{"id":"$messageId","content_type":"text","status":"sent"}}"""
        assertTrue(runCatching { repo.sendPost(lease, SharedMessageDestination.Group, scopeId, messageId, post, "Nota") }.isFailure)
        body = body.replace("\"text\"", "\"shared_content\"")
        repo.sendPost(lease, SharedMessageDestination.Group, scopeId, messageId, post, "Nota")
        assertEquals(listOf("POST", "POST"), calls.map { it.first })
        assertTrue(calls.all { it.second == "/v1/data/messages/groups/$scopeId/messages" })
        val request = parser.parseToJsonElement(postBodies.last()).toString()
        assertTrue(request.contains("\"sharedNote\":\"Nota\""))
        assertTrue(request.contains("\"sharedContent\":{\"kind\":\"post\",\"id\":\"Post-Abc123\"}"))
        assertFalse(request.contains("\"content\":"))
    }

    @Test fun theSecondResolutionCanBeDeniedAndAStaleResponseCannotOpen() = runBlocking {
        val lease = repo.captureAccount()
        body = """{"reference":{"kind":"post","id":"Post-Abc123"},"title":"Publicación","summary":"Vista previa"}"""
        val preview = repo.resolve(lease, SharedMessageDestination.Community, scopeId, messageId)
        assertEquals(post, preview.reference)
        val success = body
        reply = HttpStatusCode.NotFound
        body = """{"error":"SHARED_CONTENT_NOT_AVAILABLE"}"""
        assertTrue(runCatching { repo.resolve(lease, SharedMessageDestination.Community, scopeId, messageId) }
            .exceptionOrNull() is SharedMessageUnavailable)
        body = success
        reply = HttpStatusCode.OK
        onReply = { guard.invalidate {} }
        assertTrue(runCatching { repo.resolve(lease, SharedMessageDestination.Community, scopeId, messageId) }
            .exceptionOrNull() is CancellationException)
        assertEquals(3, calls.size)
    }
}