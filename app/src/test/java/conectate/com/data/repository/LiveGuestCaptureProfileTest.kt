package conectate.com.data.repository

import conectate.com.data.model.LiveQuality
import conectate.com.util.AppDeviceTier
import org.junit.Assert.*
import org.junit.Test

class LiveGuestCaptureProfileTest {
    @Test fun lowTierGuestDoesNotOpen720pEvenWhen1080pWasRequested() {
        assertEquals(LiveGuestCaptureProfile(640, 360, 60), nativeLiveGuestCaptureProfile(AppDeviceTier.LOW, LiveQuality.P1080))
    }
    @Test fun balancedGuestFitsItsCameraBudget() {
        assertEquals(LiveGuestCaptureProfile(960, 540, 60), nativeLiveGuestCaptureProfile(AppDeviceTier.BALANCED, LiveQuality.P720))
    }
    @Test fun highTierKeepsAnAlreadySmallerRequestedImage() {
        assertEquals(LiveGuestCaptureProfile(854, 480, 60), nativeLiveGuestCaptureProfile(AppDeviceTier.HIGH, LiveQuality.P480))
    }
    @Test fun highTierDoesNotUpscaleOrOpen1080pByDefault() {
        assertEquals(LiveGuestCaptureProfile(1280, 720, 60), nativeLiveGuestCaptureProfile(AppDeviceTier.HIGH, LiveQuality.P1080))
    }
    @Test fun allCameraDimensionsAreEvenAndPreserveAspectRatio() {
        AppDeviceTier.entries.forEach { tier -> LiveQuality.entries.forEach { requested ->
            val actual = nativeLiveGuestCaptureProfile(tier, requested)
            assertEquals(0, actual.width % 2); assertEquals(0, actual.height % 2)
            assertTrue(actual.width <= requested.width && actual.height <= requested.height)
            assertEquals(requested.width.toDouble() / requested.height, actual.width.toDouble() / actual.height, 0.01)
        } }
    }
}
