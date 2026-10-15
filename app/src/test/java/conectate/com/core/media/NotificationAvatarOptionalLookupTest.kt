package conectate.com.core.media

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationAvatarOptionalLookupTest {
    @Test fun localLookupFailureReturnsNoPhotoInsteadOfSuppressingMessage() = runBlocking {
        assertNull(NotificationAvatarLoader.optionalValue<String> { throw IOException("local fixture lookup failed") })
    }
    @Test fun successfulLookupIsPreserved() = runBlocking {
        assertEquals("photo", NotificationAvatarLoader.optionalValue { "photo" })
    }
    @Test fun accountOrScopeCancellationIsNeverSwallowed() = runBlocking {
        try {
            NotificationAvatarLoader.optionalValue<String> { throw CancellationException("fixture cancelled") }
            fail("Cancellation must propagate")
        } catch (error: CancellationException) { assertEquals("fixture cancelled", error.message) }
    }
}
