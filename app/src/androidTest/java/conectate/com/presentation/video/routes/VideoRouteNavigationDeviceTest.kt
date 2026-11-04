package conectate.com.presentation.video.routes

import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.navigation.addMainRoutes
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the registered graph and the arguments Hilt receives from the destination. */
@RunWith(AndroidJUnit4::class)
class VideoRouteNavigationDeviceTest {
    private val viewModels = ViewModelStore()

    @After fun clearNavigationState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
    }

    @Test fun theRoutePlayerReceivesItsEncodedIdentityAndBoundedModeAndReturnsToItsRoute() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val controller = newController()
            val routeEntry = checkNotNull(controller.currentBackStackEntry)
            routeEntry.savedStateHandle["route-context"] = "guitarra"
            val identity = "Imported/Video?name=Álvaro#one"
            controller.navigate(Screen.VideoRoutePlayer.createRoute(identity))
            val playerEntry = checkNotNull(controller.currentBackStackEntry)
            assertEquals(Screen.VideoRoutePlayer.route, playerEntry.destination.route)
            assertEquals(identity, playerEntry.arguments?.getString("videoId"))
            assertEquals(true, modelState(playerEntry).get<Boolean>("singleVideoOnly"))
            assertTrue(controller.popBackStack())
            assertEquals(Screen.VideoRoutes.route, controller.currentDestination?.route)
            assertSame(routeEntry, controller.currentBackStackEntry)
            assertEquals("guitarra", controller.currentBackStackEntry?.savedStateHandle?.get<String>("route-context"))
        }
    }

    @Test fun theExistingVideoPlayerRetainsItsOrdinaryModeAndClipArguments() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val controller = newController()
            controller.navigate(Screen.VideoPlayer.createRoute("Video-A", resumeMs = 2000, endMs = 7000))
            val entry = checkNotNull(controller.currentBackStackEntry)
            assertEquals(Screen.VideoPlayer.route, entry.destination.route)
            assertEquals("Video-A", entry.arguments?.getString("videoId"))
            assertEquals(2000L, entry.arguments?.getLong("resumeMs"))
            assertEquals(7000L, entry.arguments?.getLong("endMs"))
            assertNotEquals(true, modelState(entry).get<Boolean>("singleVideoOnly"))
        }
    }

    private fun newController(): NavHostController {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NavHostController(context).apply {
            setViewModelStore(viewModels)
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = Screen.VideoRoutes.route) {
                addMainRoutes(this@apply, isLoggedIn = true)
            }
        }
    }

    private class NavigationArguments(val state: SavedStateHandle) : ViewModel()

    private fun modelState(entry: NavBackStackEntry): SavedStateHandle {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                NavigationArguments(extras.createSavedStateHandle()) as T
        }
        return ViewModelProvider(entry, factory)[NavigationArguments::class.java].state
    }
}
