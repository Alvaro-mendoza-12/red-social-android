package conectate.com.data.localfallback

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.supabase.SupabaseConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Public transport checks with a deliberately invalid token: no messages can be created. */
@RunWith(AndroidJUnit4::class)
class MessageTransportRejectionTest {
    private fun checkRejection(kind: String) = runBlocking {
        assumeTrue("QA runs only on an emulator", Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.contains("sdk", ignoreCase = true) || Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupabaseConfig(context)
        val client = LocalFallbackApiClient(config)
        val room = UUID.randomUUID().toString()
        try {
            val error = withTimeout(15_000) {
                when (kind) {
                    "chat" -> client.sendChatMessage("qa-invalid-fixture", room, "QA rejected request", "text",
                        null, null, 0, null).exceptionOrNull()
                    "group" -> client.sendGroupMessage("qa-invalid-fixture", room, "QA rejected request", "text",
                        null, 0, null).exceptionOrNull()
                    else -> client.sendCommunityMessage("qa-invalid-fixture", room, "QA rejected request", "text",
                        null, 0, null).exceptionOrNull()
                }
            }
            assertTrue("$kind must preserve its HTTP rejection", error is MessageSendHttpException)
            val rejection = error as MessageSendHttpException
            assertEquals(401, rejection.statusCode)
            assertEquals("INVALID_TOKEN", rejection.errorCode)
            assertFalse(rejection.isRetryable)
        } finally {
            config.httpClient.close()
        }
    }

    @Test fun chatPreservesAnAuthenticationRejection() = checkRejection("chat")
    @Test fun groupPreservesAnAuthenticationRejection() = checkRejection("group")
    @Test fun communityPreservesAnAuthenticationRejection() = checkRejection("community")
}
