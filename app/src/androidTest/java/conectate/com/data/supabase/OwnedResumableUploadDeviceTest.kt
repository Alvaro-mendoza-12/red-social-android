package conectate.com.data.supabase

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.core.testing.MediaAuditEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in: one private, synthetic account and an unshared text file, removed in finally. */
class OwnedResumableUploadDeviceTest {
    @Test fun interruptedUploadResumesAcknowledgedBytesAndKeepsExactContent() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedUploadRecovery") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa"))
        val fixtureFile = File(context.cacheDir, "mobile96-read-fixture.json")
        val fixture = JSONObject(fixtureFile.readText())
        val previous = TestRuntimeOverrides.currentSession(context)
        TestRuntimeOverrides.setCurrentSession(context, null)
        val deps = EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java)
        val auth = deps.authService()
        val storage = deps.storageService()
        val prefs = context.getSharedPreferences("resumable_upload_sessions", 0)
        val originalKeys = prefs.all.keys.toSet()
        val source = File(context.cacheDir, "owned-upload96-${UUID.randomUUID()}.txt")
        var sessionUrl: String? = null
        var objectPath: String? = null
        var objectCreated = false
        val http = OkHttpClient.Builder().dns(conectate.com.core.network.PublicBackendDns)
            .callTimeout(30, TimeUnit.SECONDS).build()
        try {
            auth.importLocalFallbackStoredSession(fixture.getString("accessToken"), fixture.getString("refreshToken"),
                fixture.getString("email"), fixture.getString("userId"), null, null).getOrThrow()
            assertTrue(auth.isLocalFallbackSession())
            val requestedPath = "${auth.getCurrentUserId()}/audit96-${UUID.randomUUID()}.txt"
            objectPath = requestedPath
            source.outputStream().use { output -> repeat(64) { output.write(ByteArray(128 * 1024) { 'A'.code.toByte() }) } }
            val length = source.length()
            val interrupted = AtomicBoolean(false)
            val job = launch(start = CoroutineStart.LAZY) {
                storage.uploadFileFromUri("chat-media", requestedPath, Uri.fromFile(source), length, "text/plain", false) { progress ->
                    if (progress > 0.78f && progress < 0.99f && interrupted.compareAndSet(false, true)) {
                        cancel("Owned test interruption after first acknowledged chunk")
                    }
                }.getOrThrow()
            }
            job.start(); job.join()
            assertTrue("Did not reach the second upload chunk", interrupted.get())
            assertTrue(job.isCancelled)
            sessionUrl = prefs.all.entries.first { it.key !in originalKeys && it.key.endsWith(".url") }.value as String
            val token = auth.getAccessTokenAwait()!!
            val offset = http.newCall(Request.Builder().url(sessionUrl).head()
                .header("Authorization", "Bearer $token").header("Tus-Resumable", "1.0.0").build()).execute().use {
                assertEquals(200, it.code)
                it.header("Upload-Offset")!!.toLong()
            }
            assertTrue("First acknowledged chunk was lost", offset >= 6L * 1024 * 1024)
            assertTrue("Interruption occurred after the complete upload", offset < length)
            var firstRetryProgress: Float? = null
            val result = storage.uploadFileFromUri("chat-media", requestedPath, Uri.fromFile(source), length, "text/plain", false) {
                if (firstRetryProgress == null) firstRetryProgress = it
            }.getOrThrow()
            val uploadedPath = result.Key ?: error("Missing uploaded object path")
            objectPath = uploadedPath
            objectCreated = true
            assertTrue("Retry started again at zero", firstRetryProgress!! >= offset.toFloat() / length)
            val publicUrl = storage.getPublicUrl("chat-media", uploadedPath)
            val expected = source.inputStream().use { uploadSourceSha256(it, length) }
            val actual = http.newCall(Request.Builder().url(publicUrl).header("Authorization", "Bearer $token").build()).execute().use {
                assertEquals(200, it.code)
                it.body!!.byteStream().use { stream -> uploadSourceSha256(stream, length) }
            }
            assertEquals(expected, actual)
            File(context.getExternalFilesDir("mobile96"), "upload-recovery.json").writeText(JSONObject()
                .put("bytes", length).put("acknowledgedBytesBeforeRetry", offset)
                .put("firstRetryProgress", firstRetryProgress).put("exactContentSha256Matched", true)
                .put("scope", "One owned text file; cancellation during second chunk, actual app storage service and production TUS endpoint").toString(2))
        } finally {
            sessionUrl = sessionUrl ?: prefs.all.entries.firstOrNull { it.key !in originalKeys && it.key.endsWith(".url") }?.value as? String
            val token = auth.getAccessTokenAwait()
            // Termination is owner-authenticated. Completed sessions may already be gone.
            try {
                if (sessionUrl != null && token != null) http.newCall(Request.Builder().url(sessionUrl).delete()
                    .header("Authorization", "Bearer $token").header("Tus-Resumable", "1.0.0").build()).execute().use {
                    check(it.code in listOf(204, 404, 410)) { "Owned upload session cleanup failed: ${it.code}" }
                }
                if (objectCreated) objectPath?.let { storage.deleteFile("chat-media", it).getOrThrow() }
            } finally {
                prefs.edit().also { editor -> prefs.all.keys.filter { it !in originalKeys }.forEach(editor::remove) }.commit()
                source.delete(); fixtureFile.delete()
                try { auth.signOut() } finally { TestRuntimeOverrides.setCurrentSession(context, previous) }
            }
        }
    }
}
