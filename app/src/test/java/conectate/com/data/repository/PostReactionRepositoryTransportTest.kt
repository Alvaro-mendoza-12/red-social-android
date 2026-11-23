package conectate.com.data.repository

import androidx.test.core.app.ApplicationProvider
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.test.UnitTestApplication
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PostReactionRepositoryTransportTest {
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private val cache = mockk<UpstashCacheService>()
    private val guard = AuthSessionMutationGuard()
    private val parser = Json { ignoreUnknownKeys = true }
    private val calls = mutableListOf<HttpMethod>()
    private var denyWrite = false
    private var saved = false

    private val client = HttpClient(MockEngine { request ->
        calls += request.method
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.method) {
            HttpMethod.Post -> {
                assertEquals("/v1/data/feed/posts/post-1/reaction", request.url.encodedPath)
                assertEquals("minimal", request.url.parameters["response"])
                if (denyWrite) {
                    respond("""{"error":"FORBIDDEN"}""", HttpStatusCode.Forbidden, headers)
                } else {
                    saved = true
                    throw IOException("Response closed after commit")
                }
            }
            HttpMethod.Get -> {
                assertEquals("/v1/data/feed/posts/post-1", request.url.encodedPath)
                assertTrue(saved)
                respond(
                    """{"post":{"id":"post-1","user_id":"author","my_reaction":"LOVE","liked_by_me":true}}""",
                    HttpStatusCode.OK,
                    headers,
                )
            }
            else -> error("Unexpected reaction request")
        }
    }) { install(ContentNegotiation) { json(parser) } }

    private lateinit var repository: PostRepository

    @Before
    fun setup() {
        mockkObject(RuntimeBackendEndpoint)
        every { RuntimeBackendEndpoint.baseUrl } returns "https://fixture.invalid"
        every { RuntimeBackendEndpoint.isConfigured } returns true
        coEvery { RuntimeBackendEndpoint.ensureFreshEndpoint() } returns "https://fixture.invalid"
        every { config.httpClient } returns client
        every { config.json } returns parser
        every { config.isLocalFallbackPrimary } returns true
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { "viewer" } }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("synthetic-token")
        coEvery { cache.delete(any()) } returns true
        every { auth.commitForAccountLease(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        repository = PostRepository(
            supabaseConfig = config,
            authService = auth,
            storageService = mockk(),
            moderationService = mockk(),
            appMetrics = mockk(),
            notificationRepository = mockk(),
            userRepository = mockk(),
            hashtagRepository = mockk(),
            upstashCache = cache,
            localFallbackApiClient = LocalFallbackApiClient(config),
            context = ApplicationProvider.getApplicationContext(),
        )
    }

    @After
    fun cleanup() {
        client.close()
        unmockkObject(RuntimeBackendEndpoint)
    }

    @Test
    fun repositoryConfirmsTheLostResponseWithoutResendingTheWrite() = runBlocking {
        assertTrue(repository.setPostReaction("post-1", "LOVE", wasReacted = false).isSuccess)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get), calls)
    }

    @Test
    fun repositoryPropagatesTheExplicitRejectionWithoutReading() = runBlocking {
        denyWrite = true
        assertTrue(repository.setPostReaction("post-1", "LOVE", wasReacted = false).isFailure)
        assertEquals(listOf(HttpMethod.Post), calls)
    }
}
