package conectate.com.data.livekit

import conectate.com.util.AppDeviceTier
import org.junit.Assert.*
import org.junit.Test

class LiveCollaborationQualityProfileTest {
    @Test fun mobileUplinksStartWithOneCompactThirtyFpsEncoding() {
        AppDeviceTier.entries.forEach { tier ->
            val profile = liveCollaborationNetworkProfile(tier, true)
            assertEquals(640, profile.width); assertEquals(360, profile.height)
            assertEquals(30, profile.maxFps); assertEquals(450_000, profile.maxBitrate)
            assertFalse(profile.simulcast)
        }
    }
    @Test fun unmeteredNetworksKeepTheDeviceBudget() {
        AppDeviceTier.entries.forEach { tier ->
            assertEquals(liveCollaborationQualityProfile(tier), liveCollaborationNetworkProfile(tier, false))
        }
    }
    @Test fun smallDevicesUseOneCompactEncoding() {
        val profile = liveCollaborationQualityProfile(AppDeviceTier.LOW)
        assertEquals(640, profile.width)
        assertEquals(360, profile.height)
        assertFalse(profile.simulcast)
        assertEquals(60, profile.maxFps)
    }
    @Test fun largerDevicesRetainAdaptiveLayers() {
        assertTrue(liveCollaborationQualityProfile(AppDeviceTier.BALANCED).simulcast)
        assertEquals(1280, liveCollaborationQualityProfile(AppDeviceTier.HIGH).width)
        assertEquals(720, liveCollaborationQualityProfile(AppDeviceTier.HIGH).height)
        assertEquals(60, liveCollaborationQualityProfile(AppDeviceTier.HIGH).maxFps)
    }
    @Test fun encodingCostIncreasesWithTheDeviceBudget() {
        val profiles = AppDeviceTier.entries.map(::liveCollaborationQualityProfile)
        profiles.zipWithNext().forEach { (small, large) ->
            assertTrue(small.width * small.height < large.width * large.height)
            assertTrue(small.maxBitrate < large.maxBitrate)
        }
    }
}
