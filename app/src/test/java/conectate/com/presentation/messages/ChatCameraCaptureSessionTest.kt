package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class ChatCameraCaptureSessionTest {
    @Test fun closingRejectsLatePhotoAndPermissionCallbacks() {
        val session = ChatCameraCaptureSession()
        val token = session.begin()!!
        session.close()
        assertFalse(session.accepts(token))
        assertNull(session.begin())
    }
    @Test fun backgroundingInvalidatesCaptureEvenAfterReturning() {
        val session = ChatCameraCaptureSession()
        val token = session.begin()!!
        session.stop()
        assertNull(session.begin())
        session.start()
        assertFalse(session.accepts(token))
        assertTrue(session.accepts(session.begin()!!))
    }
    @Test fun aNewCaptureRejectsAnOlderResult() {
        val session = ChatCameraCaptureSession()
        val first = session.begin()!!
        val second = session.begin()!!
        assertFalse(session.accepts(first))
        assertTrue(session.accepts(second))
    }
    @Test fun aClosedScreenCannotBeReopenedByALifecycleCallback() {
        val session = ChatCameraCaptureSession(false)
        assertNull(session.begin())
        session.start()
        assertNotNull(session.begin())
        session.close()
        session.start()
        assertNull(session.begin())
    }
}
