package conectate.com.data.localfallback

import conectate.com.data.supabase.SupabaseConfig
import conectate.com.domain.auth.PasswordResetConfirmedNeedsSignInException
import conectate.com.test.UnitTestApplication
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class LocalFallbackPasswordResetReceiptTest {
    private val clients = mutableListOf<HttpClient>()
    @Before fun configureEndpoint() {
        mockkObject(RuntimeBackendEndpoint)
        every { RuntimeBackendEndpoint.isConfigured } returns true
        coEvery { RuntimeBackendEndpoint.authenticationEndpoint() } returns "https://qa169.example.invalid"
    }
    @After fun closeFixtures() {
        clients.forEach { it.close() }
        unmockkObject(RuntimeBackendEndpoint)
    }

    @OptIn(InternalAPI::class)
    private class FixtureEngine(private val payload: String, private val status: HttpStatusCode) : HttpClientEngineBase("qa169-password-reset") {
        override val config = HttpClientEngineConfig()
        var requests = 0
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            requests++
            assertEquals("/v1/auth/password-reset/confirm", data.url.encodedPath)
            return HttpResponseData(status, GMTDate(), Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                HttpProtocolVersion.HTTP_1_1, ByteReadChannel(payload), callContext())
        }
    }
    private suspend fun confirm(payload: String, status: HttpStatusCode = HttpStatusCode.OK): Result<LocalFallbackAuthResponse> {
        val parser = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val engine = FixtureEngine(payload, status)
        val client = HttpClient(engine) { install(ContentNegotiation) { json(parser) } }.also { clients += it }
        val config = mockk<SupabaseConfig>()
        every { config.json } returns parser
        every { config.httpClient } returns client
        val result = withTimeout(10_000) {
            LocalFallbackApiClient(config).confirmPasswordReset("qa169-fixture-grant", "QA169-fixture-password")
        }
        assertEquals("A confirmation must never be replayed automatically", 1, engine.requests)
        return result
    }

    @Test fun confirmedReceiptWithoutSessionHasTypedSignInContinuation() = runBlocking {
        val result = confirm("""{"ok":true,"passwordChanged":true,"requiresSignIn":true}""")
        assertTrue(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun ordinarySuccessfulSessionRemainsCompatible() = runBlocking {
        val result = confirm("""{"user":{"id":"11111111-1111-4111-8111-111111111169","email":"qa169@example.invalid","displayName":"QA169"},"accessToken":"qa169-access","refreshToken":"qa169-refresh"}""")
        assertEquals("qa169-access", result.getOrThrow().accessToken)
    }
    @Test fun existingSecondFactorContinuationRemainsRequired() = runBlocking {
        assertTrue(confirm("""{"ok":true,"requiresTwoFactor":true}""").exceptionOrNull() is LocalFallbackPasswordChangedNeedsTwoFactorException)
    }
    @Test fun serverErrorCannotManufacturePasswordConfirmation() = runBlocking {
        val result = confirm("""{"ok":true,"passwordChanged":true,"requiresSignIn":true}""", HttpStatusCode.InternalServerError)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun stringFlagsAreNotAnExplicitBooleanAcknowledgement() = runBlocking {
        val result = confirm("""{"ok":true,"passwordChanged":"true","requiresSignIn":"true"}""")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun signInFlagAloneDoesNotConsumeAResetGrant() = runBlocking {
        val result = confirm("""{"ok":true,"requiresSignIn":true}""")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun malformedBodyDoesNotAcknowledgeAnything() = runBlocking {
        val result = confirm("not json")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun declinedResponseWithFlagsIsNotAConfirmation() = runBlocking {
        val result = confirm("""{"ok":false,"passwordChanged":true,"requiresSignIn":true}""")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
    @Test fun changedFlagWithoutSignInContinuationIsNotAnIncompleteSessionReceipt() = runBlocking {
        val result = confirm("""{"ok":true,"passwordChanged":true,"requiresSignIn":false}""")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is PasswordResetConfirmedNeedsSignInException)
    }
}
