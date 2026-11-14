package conectate.com.presentation.navigation

import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRouteExitTest {
    private fun controller() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        setViewModelStore(ViewModelStore())
        graph = createGraph(startDestination = Screen.Settings.route) { addMainRoutes(this@apply, isLoggedIn = true) }
    }

    @Test fun theCurrentViewerStillReturnsToItsPreviousScreen() {
        val nav = controller()
        nav.navigate(Screen.LiveViewer.createRoute("11111111-1111-4111-8111-111111111111"))
        assertTrue(nav.popLiveRoute(nav.currentBackStackEntry!!.id))
        assertEquals(Screen.Settings.route, nav.currentDestination?.route)
    }

    @Test fun aRepeatedExitCannotPopOrRedirectTheFollowingScreen() {
        val nav = controller()
        nav.navigate(Screen.LiveViewer.createRoute("11111111-1111-4111-8111-111111111111"))
        val oldEntry = nav.currentBackStackEntry!!.id
        assertTrue(nav.popLiveRoute(oldEntry))
        val nextEntry = nav.currentBackStackEntry!!.id
        assertFalse(nav.popLiveRoute(oldEntry, Screen.LiveDiscovery.route))
        assertEquals(nextEntry, nav.currentBackStackEntry!!.id)
        assertEquals(Screen.Settings.route, nav.currentDestination?.route)
    }

    @Test fun reopeningTheIdenticalLiveRouteCreatesADifferentExitOwner() {
        val nav = controller()
        val route = Screen.LiveViewer.createRoute("11111111-1111-4111-8111-111111111111")
        nav.navigate(route)
        val oldEntry = nav.currentBackStackEntry!!.id
        assertTrue(nav.popBackStack())
        nav.navigate(route)
        val nextEntry = nav.currentBackStackEntry!!.id
        assertNotEquals(oldEntry, nextEntry)
        assertFalse(nav.popLiveRoute(oldEntry, Screen.LiveDiscovery.route))
        assertEquals(nextEntry, nav.currentBackStackEntry!!.id)
    }

    @Test fun aDelayedHostClosureDoesNotPopTheViewerOpenedWhileItWaits() = runTest {
        val nav = controller()
        nav.navigate(Screen.LiveHost.createRoute("11111111-1111-4111-8111-111111111111"))
        val oldEntry = nav.currentBackStackEntry!!.id
        val nativeRelease = CompletableDeferred<Unit>()
        val exit = async { nativeRelease.await(); nav.popLiveRoute(oldEntry) }
        runCurrent()
        nav.navigate(Screen.LiveViewer.createRoute("22222222-2222-4222-8222-222222222222"))
        val nextEntry = nav.currentBackStackEntry!!.id
        nativeRelease.complete(Unit); runCurrent()
        assertFalse(exit.await())
        assertEquals(nextEntry, nav.currentBackStackEntry!!.id)
        assertEquals(Screen.LiveViewer.route, nav.currentDestination?.route)
    }

    @Test fun aCurrentRootEntryCanStillUseItsFallback() {
        val nav = controller()
        nav.graph = nav.createGraph(startDestination = "viewer-root") {
            composable("viewer-root") {}
            composable("discovery") {}
        }
        assertNull(nav.previousBackStackEntry)
        assertTrue(nav.popLiveRoute(nav.currentBackStackEntry!!.id, "discovery"))
        assertEquals("discovery", nav.currentDestination?.route)
    }
}
