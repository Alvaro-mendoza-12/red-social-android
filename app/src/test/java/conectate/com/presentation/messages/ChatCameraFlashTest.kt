package conectate.com.presentation.messages

import androidx.camera.core.ImageCapture
import org.junit.Assert.*
import org.junit.Test

class ChatCameraFlashTest {
    @Test fun photosCycleThroughVisibleOnAutoAndOff() {
        assertEquals(ImageCapture.FLASH_MODE_ON, nextChatCameraFlash(ImageCapture.FLASH_MODE_OFF, false))
        assertEquals(ImageCapture.FLASH_MODE_AUTO, nextChatCameraFlash(ImageCapture.FLASH_MODE_ON, false))
        assertEquals(ImageCapture.FLASH_MODE_OFF, nextChatCameraFlash(ImageCapture.FLASH_MODE_AUTO, false))
    }
    @Test fun videoNeverEntersAutomaticPhotoFlash() {
        assertEquals(ImageCapture.FLASH_MODE_ON, nextChatCameraFlash(ImageCapture.FLASH_MODE_OFF, true))
        assertEquals(ImageCapture.FLASH_MODE_OFF, nextChatCameraFlash(ImageCapture.FLASH_MODE_ON, true))
    }
    @Test fun continuousLightRequiresSupportedHardwareAndExplicitOn() {
        assertTrue(chatCameraUsesTorch(ImageCapture.FLASH_MODE_ON, true))
        assertFalse(chatCameraUsesTorch(ImageCapture.FLASH_MODE_ON, false))
        assertFalse(chatCameraUsesTorch(ImageCapture.FLASH_MODE_AUTO, true))
        assertFalse(chatCameraUsesTorch(ImageCapture.FLASH_MODE_OFF, true))
    }
    @Test fun descriptionsDifferentiateUnavailablePendingAutoAndActualLight() {
        assertEquals("Sin flash", chatCameraFlashLabel(ImageCapture.FLASH_MODE_ON, false, false, false))
        assertEquals("Preparando flash…", chatCameraFlashLabel(ImageCapture.FLASH_MODE_ON, true, false, true))
        assertEquals("Flash automático", chatCameraFlashLabel(ImageCapture.FLASH_MODE_AUTO, true, false, false))
        assertEquals("Flash encendido", chatCameraFlashLabel(ImageCapture.FLASH_MODE_ON, true, true, false))
        assertNotEquals("Flash encendido", chatCameraFlashLabel(ImageCapture.FLASH_MODE_ON, true, false, false))
    }
}
