package conectate.com.data.localfallback
import org.junit.Assert.*
import org.junit.Test
class PendingMessagePermissionPolicyTest {
    @Test fun pendingAcceptanceCanRetryWithoutChangingItsIdOrContent() { assertTrue(isPendingMessagePermission("{\"error\":\"MESSAGE_REQUEST_PENDING\"}")) }
    @Test fun rejectionAndConflictAreNotConfusedWithTemporaryPermissionWait() {
        assertFalse(isPendingMessagePermission("MESSAGE_REQUEST_REJECTED"));assertFalse(isPendingMessagePermission("MESSAGE_ID_CONFLICT"));assertFalse(isPendingMessagePermission(null))
    }
}
