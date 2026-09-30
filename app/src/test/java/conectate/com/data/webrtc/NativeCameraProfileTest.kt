package conectate.com.data.webrtc

import conectate.com.util.AppDeviceTier
import org.junit.Assert.*
import org.junit.Test

class NativeCameraProfileTest {
    @Test fun lowTierBoundsFullHdAndPreservesCadence() {
        assertEquals(NativeCameraProfile(640, 360, 30), nativeCameraProfile(AppDeviceTier.LOW, 1920, 1080, 30))
    }
    @Test fun portraitKeepsItsAspectRatio() {
        assertEquals(NativeCameraProfile(360, 640, 24), nativeCameraProfile(AppDeviceTier.LOW, 1080, 1920, 24))
    }
    @Test fun lowerUserRequestIsRespected() {
        assertEquals(NativeCameraProfile(320, 240, 12), nativeCameraProfile(AppDeviceTier.HIGH, 320, 240, 12))
    }
    @Test fun dimensionsArePositiveAndEven() {
        for (tier in AppDeviceTier.entries) {
            val profile = nativeCameraProfile(tier, -1, 0, 100)
            assertTrue(profile.width > 0 && profile.height > 0)
            assertEquals(0, profile.width % 2)
            assertEquals(0, profile.height % 2)
        }
    }
    @Test fun fallbacksOnlyReduceDimensionsAndKeepFps() {
        val preferred = nativeCameraProfile(AppDeviceTier.LOW, 640, 480, 24)
        val profiles = nativeCameraFallbacks(preferred)
        assertEquals(preferred, profiles.first())
        assertEquals(3, profiles.size)
        profiles.zipWithNext().forEach { (before, after) ->
            assertTrue(after.width <= before.width && after.height <= before.height)
            assertEquals(before.fps, after.fps)
        }
    }
    @Test fun liveSixtyFpsIsRequestedForEveryDeviceTier() {
        AppDeviceTier.entries.forEach {
            assertEquals(60, nativeCameraProfile(it, 1920, 1080, 60).fps)
        }
    }
    @Test fun supportedSixtyFpsUsesSmallerNativeFormatWhenNeeded() {
        assertEquals(NativeCameraProfile(640, 360, 60), supportedNativeCameraProfile(
            NativeCameraProfile(1280, 720, 60), listOf(
                NativeCameraFormat(1280, 720, 30), NativeCameraFormat(640, 360, 60))))
    }
    @Test fun unsupportedSixtyFpsUsesTheRealHardwareLimit() {
        assertEquals(30, supportedNativeCameraProfile(NativeCameraProfile(640, 360, 60),
            listOf(NativeCameraFormat(640, 480, 30))).fps)
    }
    @Test fun absentOrInvalidCameraMetadataDoesNotInventSixtyFps() {
        assertEquals(30, supportedNativeCameraProfile(NativeCameraProfile(640, 360, 60), emptyList()).fps)
        assertEquals(24, supportedNativeCameraProfile(NativeCameraProfile(640, 360, 24),
            listOf(NativeCameraFormat(0, 0, 0))).fps)
    }
    @Test fun portraitAndLowerCadenceArePreservedWithNativeFormat() {
        assertEquals(NativeCameraProfile(360, 640, 15), supportedNativeCameraProfile(
            NativeCameraProfile(360, 640, 15), listOf(NativeCameraFormat(640, 360, 60))))
    }
}
