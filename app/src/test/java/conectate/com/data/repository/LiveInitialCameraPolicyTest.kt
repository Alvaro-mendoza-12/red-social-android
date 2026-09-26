package conectate.com.data.repository

import conectate.com.data.model.LiveSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInitialCameraPolicyTest {
    @Test
    fun `camera source respects creator choice`() {
        assertTrue(resolvedInitialHostVideoEnabled(LiveSource.CAMERA, true))
        assertFalse(resolvedInitialHostVideoEnabled(LiveSource.CAMERA, false))
    }

    @Test
    fun `audio source never enables video`() {
        assertFalse(resolvedInitialHostVideoEnabled(LiveSource.AUDIO, true))
        assertFalse(resolvedInitialHostVideoEnabled(LiveSource.AUDIO, null))
    }

    @Test
    fun `legacy setup defaults on while screen setup can disable video`() {
        assertTrue(resolvedInitialHostVideoEnabled(LiveSource.CAMERA, null))
        assertFalse(resolvedInitialHostVideoEnabled(LiveSource.SCREEN, false))
    }
}
