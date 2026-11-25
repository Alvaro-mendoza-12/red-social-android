package conectate.com.presentation.social

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.repository.MomentRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.supabase.StorageResponse
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MomentPublicationTest {
    private val dispatcher = StandardTestDispatcher()
    private val guard = AuthSessionMutationGuard()
    @Volatile private var account = "owner"
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private val storage = mockk<StorageService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val publications = AtomicInteger()
    private val feedReads = AtomicInteger()
    private val uploads = AtomicInteger()
    private val feedGate = CompletableDeferred<Unit>()
    private val postReached = CompletableDeferred<Unit>()
    @Volatile private var postGate: CompletableDeferred<Unit>? = null
    @Volatile private var rejectPost = false
    @Volatile private var afterUpload: (() -> Unit)? = null
    private var successes = 0
    private val errors = mutableListOf<String>()
    private lateinit var photo: File
    private lateinit var client: HttpClient
    private lateinit var model: CreateMomentViewModel
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(RuntimeBackendEndpoint)
        every { RuntimeBackendEndpoint.baseUrl } returns "https://fixture.invalid"
        every { RuntimeBackendEndpoint.isConfigured } returns true
        coEvery { RuntimeBackendEndpoint.ensureFreshEndpoint() } returns "https://fixture.invalid"
        every { auth.currentSession } returns MutableStateFlow(null)
        every { auth.getCurrentUserId() } answers { account }
        every { auth.getAccessToken() } answers { "$account-token" }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { auth.getAccessTokenForAccountAwait("owner") } returns "owner-token"
        coEvery { storage.uploadFile("moment-media", any(), any(), "image/png", expectedAccountId = "owner") } coAnswers {
            uploads.incrementAndGet()
            afterUpload?.invoke()
            Result.success(StorageResponse(Key = secondArg()))
        }
        every { storage.getPublicUrl("moment-media", any()) } returns "https://fixture.invalid/photo.png"

        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        client = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/v1/data/moments" -> {
                    assertEquals("Bearer owner-token", request.headers[HttpHeaders.Authorization])
                    publications.incrementAndGet()
                    postReached.complete(Unit)
                    postGate?.await()
                    if (rejectPost) respond("""{"error":"temporalmente no disponible"}""", HttpStatusCode.ServiceUnavailable, headers)
                    else respond("""{"moment":{"id":"confirmed","user_id":"owner","media_url":"https://fixture.invalid/photo.png","media_type":"image"}}""", headers = headers)
                }
                request.url.encodedPath == "/v1/data/moments/feed" -> {
                    feedReads.incrementAndGet()
                    feedGate.await()
                    respond("""{"items":[]}""", headers = headers)
                }
                else -> error("Unexpected test request ${request.method} ${request.url.encodedPath}")
            }
        }) { install(ContentNegotiation) { json(this@MomentPublicationTest.json) } }
        every { config.json } returns json
        every { config.httpClient } returns client
        every { config.isLocalFallbackPrimary } returns true
        val repository = MomentRepository(config, auth, LocalFallbackApiClient(config), mockk(), storage, application)
        model = CreateMomentViewModel(repository, mockk(), auth, mockk())
        photo = File.createTempFile("moment-publish-", ".png", application.cacheDir)
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            photo.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    @After
    fun tearDown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        postGate?.complete(Unit)
        feedGate.complete(Unit)
        if (::client.isInitialized) client.close()
        if (::photo.isInitialized) photo.delete()
        unmockkObject(RuntimeBackendEndpoint)
        Dispatchers.resetMain()
    }

    private fun publish() {
        model.uploadMoment(
            application, Uri.fromFile(photo), "image", 24, "24h", "all", "public", emptyList(),
            onSuccess = { successes++ },
            onError = { errors += it },
        )
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        do {
            dispatcher.scheduler.runCurrent()
            if (condition()) return@withTimeout
            delay(5)
        } while (true)
    }

    private fun switchTo(next: String) {
        guard.invalidate { account = next }
    }

    @Test
    fun duplicateTapSendsOnceAndConfirmationDoesNotWaitForAFeedRead() = runBlocking {
        publish()
        publish()
        assertTrue(model.isUploading.value)
        waitFor { successes == 1 && feedReads.get() == 1 }
        assertFalse(feedGate.isCompleted)
        assertEquals(1, publications.get())
        assertEquals(1, uploads.get())
        assertTrue(errors.isEmpty())
        assertFalse(model.isUploading.value)
    }

    @Test
    fun aRejectedPublicationShowsOneErrorAndAllowsRetry() = runBlocking {
        rejectPost = true
        publish()
        waitFor { !model.isUploading.value }
        assertEquals(1, errors.size)
        assertEquals(0, successes)
        assertEquals(0, feedReads.get())

        rejectPost = false
        publish()
        waitFor { successes == 1 }
        assertEquals(2, publications.get())
        assertEquals(1, errors.size)
    }

    @Test
    fun cancellationBeforeDispatchClearsProgressWithoutPosting() {
        publish()
        model.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        assertFalse(model.isUploading.value)
        assertEquals(0, uploads.get())
        assertEquals(0, publications.get())
        assertTrue(errors.isEmpty())
        assertEquals(0, successes)
    }

    @Test
    fun signingBackIntoTheSameAccountDuringUploadCannotPostFromTheOldEditor() = runBlocking {
        afterUpload = {
            switchTo("other")
            switchTo("owner")
        }
        publish()
        waitFor { !model.isUploading.value }
        assertEquals(1, uploads.get())
        assertEquals(0, publications.get())
        assertEquals(0, successes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aConfirmationFromAnotherAccountVisitDoesNotUpdateTheEditorOrReloadTheFeed() = runBlocking {
        postGate = CompletableDeferred()
        publish()
        waitFor { postReached.isCompleted }
        switchTo("other")
        postGate?.complete(Unit)
        waitFor { !model.isUploading.value }
        assertEquals(1, publications.get())
        assertEquals(0, feedReads.get())
        assertEquals(0, successes)
        assertTrue(errors.isEmpty())
    }
}
