package conectate.com.data.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class StableCameraFpsSelectorTest {
    private fun choose(requested: Int, vararg ranges: Pair<Int, Int>) = StableCameraFpsSelector.selectIndex(
        ranges.map { it.first }.toIntArray(), ranges.map { it.second }.toIntArray(), requested)

    @Test fun fixedThirtyBeatsFiveToThirty() { assertEquals(1, choose(30_000, 5_000 to 30_000, 30_000 to 30_000)) }
    @Test fun realSixtyIsPreferred() { assertEquals(2, choose(60_000, 30_000 to 30_000, 30_000 to 60_000, 60_000 to 60_000)) }
    @Test fun unsupportedSixtyUsesAdvertisedThirty() { assertEquals(1, choose(60_000, 5_000 to 30_000, 30_000 to 30_000)) }
    @Test fun lowerRequestKeepsItsOwnFixedRange() { assertEquals(1, choose(15_000, 5_000 to 30_000, 15_000 to 15_000, 30_000 to 30_000)) }
    @Test fun variableOnlyCameraUsesHighestSupportedFloor() { assertEquals(1, choose(30_000, 5_000 to 30_000, 15_000 to 30_000)) }
    @Test fun lowerRequestDoesNotForceUnsupportedFloor() { assertEquals(0, choose(10_000, 5_000 to 30_000, 30_000 to 30_000)) }
    @Test fun equidistantUpperLimitPrefersLowerCadence() { assertEquals(0, choose(25_000, 20_000 to 20_000, 30_000 to 30_000)) }
    @Test fun invalidRangeIsIgnored() { assertEquals(1, choose(30_000, 40_000 to 30_000, 30_000 to 30_000)) }
    @Test(expected = IllegalArgumentException::class) fun emptyCapabilitiesAreRejected() { choose(30_000) }
    @Test(expected = IllegalArgumentException::class) fun invalidRequestIsRejected() { choose(0, 30_000 to 30_000) }
}
