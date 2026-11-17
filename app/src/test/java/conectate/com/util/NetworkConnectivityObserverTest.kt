package conectate.com.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import conectate.com.test.UnitTestApplication

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = UnitTestApplication::class)
class NetworkConnectivityObserverTest {
    private class Fixture {
        val manager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val oldNetwork = mockk<Network>()
        var current: Network? = network
        var validated = true
        val callback = slot<ConnectivityManager.NetworkCallback>()
        val observer: NetworkConnectivityObserver
        init {
            val context = mockk<Context>()
            every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
            every { manager.activeNetwork } answers { current }
            every { manager.getNetworkCapabilities(any()) } answers { capabilities(validated) }
            every { manager.registerDefaultNetworkCallback(capture(callback)) } just Runs
            every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
            observer = NetworkConnectivityObserver(context)
        }
        fun capabilities(usable: Boolean, cellular: Boolean = false) = mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns usable
            every { hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns cellular
            every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns false
        }
    }

    @Test fun oldNetworksValidatedCallbackCannotMarkTheCurrentNetworkOnline() = runTest {
        val f = Fixture()
        f.validated = false
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        f.callback.captured.onCapabilitiesChanged(f.oldNetwork, f.capabilities(true)); runCurrent()
        assertEquals(false, states.last())
    }

    @Test fun oldNetworksUnvalidatedCallbackCannotMarkTheCurrentNetworkOffline() = runTest {
        val f = Fixture()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        f.callback.captured.onCapabilitiesChanged(f.oldNetwork, f.capabilities(false)); runCurrent()
        assertEquals(true, states.last())
    }

    @Test fun unavailableCallbackDoesNotHideANewValidatedDefaultNetwork() = runTest {
        val f = Fixture()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        f.callback.captured.onUnavailable(); runCurrent()
        assertEquals(true, states.last())
    }

    @Test fun losingTheLastNetworkStillPublishesOffline() = runTest {
        val f = Fixture()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent(); f.current = null
        f.callback.captured.onLost(f.network); runCurrent()
        assertEquals(listOf(true, false), states)
        assertFalse(f.observer.isNetworkAvailable())
    }

    @Test fun lossDoesNotStayOnlineWhenTheSynchronousSnapshotStillReturnsTheLostNetwork() = runTest {
        val f = Fixture()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        // Android may still return the old active network while delivering onLost.
        f.callback.captured.onLost(f.network); runCurrent()
        assertEquals(listOf(true, false), states)
    }

    @Test fun availabilityWaitsForCapabilitiesInsteadOfPublishingAnOlderSnapshot() = runTest {
        val f = Fixture()
        f.validated = false
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        f.validated = true // The synchronous getter now disagrees with the new network's callback.
        f.callback.captured.onAvailable(f.oldNetwork); runCurrent()
        assertEquals(listOf(false), states)
        f.callback.captured.onCapabilitiesChanged(f.oldNetwork, f.capabilities(true)); runCurrent()
        assertEquals(listOf(false, true), states)
    }

    @Test fun cellularChangesUseTheCallbackCapabilitiesInsteadOfTheOldTransport() = runTest {
        val f = Fixture()
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isCellular.collect { states += it } }
        runCurrent()
        f.callback.captured.onAvailable(f.oldNetwork)
        f.callback.captured.onCapabilitiesChanged(f.oldNetwork, f.capabilities(true, cellular = true)); runCurrent()
        assertEquals(listOf(false, true), states)
    }

    @Test @Config(sdk = [24]) fun olderAndroidCanRecoverWithoutAnImmediateCapabilitiesCallback() = runTest {
        val f = Fixture()
        f.validated = false
        val states = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.observer.isConnected.collect { states += it } }
        runCurrent()
        f.validated = true
        f.callback.captured.onAvailable(f.oldNetwork); runCurrent()
        assertEquals(listOf(false, true), states)
    }
}
