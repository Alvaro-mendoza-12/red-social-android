package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CollectionMessageForwardingSupportTest {
    private suspend fun forward(status: String = "sent", type: String = "text", url: String? = null,
        deleted: Boolean = false, transport: suspend (String) -> Result<Unit>): Result<Int> =
        CollectionMessageForwardingSupport.forwardMessage(deleted, "source", listOf("one", "two"), 20,
            "Empty", "Too many", "Failed", type, status, url, transport)

    @Test fun unsentAndLocalSourcesNeverReachAnUploadOrSendThatCouldConsumeTheirFile() = runTest {
        var calls = 0
        for (status in listOf("sending", "pending", "failed", "unknown")) {
            assertTrue(forward(status = status) { calls++; Result.success(Unit) }.isFailure)
        }
        for (uri in listOf("file:///private/original.pptx", "content://provider/original", " FILE:///original ")) {
            assertTrue(forward(type = "document", url = uri) { calls++; Result.success(Unit) }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test fun deletedAndViewOnceMessagesAreRejectedEvenByTheLegacyRepositoryPath() = runTest {
        var calls = 0
        assertTrue(forward(deleted = true) { calls++; Result.success(Unit) }.isFailure)
        for (type in listOf("view_once_image", "view_once_video", " VIEW_ONCE_OTHER ", "poll", "raffle", " SYSTEM_MEMBER_LEFT "))
            assertTrue(forward(type = type) { calls++; Result.success(Unit) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun confirmedRemoteFilesRemainForwardable() = runTest {
        val targets = mutableListOf<String>()
        for (status in listOf("sent", "delivered", " READ ", "received")) {
            val result = forward(status = status, type = "document", url = "https://media.example/original.pptx") {
                targets += it; Result.success(Unit)
            }
            assertEquals(2, result.getOrThrow())
        }
        assertEquals(List(4) { listOf("one", "two") }.flatten(), targets)
    }

    @Test fun wrappedCancellationStopsBeforeAnyFurtherRecipient() = runTest {
        var calls = 0
        val error = runCatching { forward { calls++; Result.failure(CancellationException("Closed")) } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, calls)
    }
}
