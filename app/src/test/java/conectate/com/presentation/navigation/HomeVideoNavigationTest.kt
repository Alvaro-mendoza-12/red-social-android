package conectate.com.presentation.navigation

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class HomeVideoNavigationTest {
    private fun controller() = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
    }

    private fun root() = controller().apply {
        graph = createGraph(startDestination = Screen.Home.route) {
            composable(Screen.Home.route) { }
            addMainRoutes(this@apply, isLoggedIn = true)
        }
    }

    @Test fun tabControllerCannotResolveTheRelatedSearchDestination() {
        val tabs = controller().apply {
            graph = createGraph(startDestination = Screen.Videos.route) {
                composable(Screen.Videos.route) { }
            }
        }
        // Reproduce the actual NavController exception from the previous Home
        // wiring. A missing destination must not be hidden with a catch in UI.
        assertThrows(IllegalArgumentException::class.java) {
            tabs.navigate(Screen.SearchUsers.createRoute("Kirito"))
        }
        assertEquals(Screen.Videos.route, tabs.currentDestination?.route)
    }

    @Test fun relatedSearchUsesTheRegisteredRootRouteAndPreservesItsQuery() {
        val root = root()
        for (query in listOf("Kirito", "café & música / 100%", "東京 #アニメ")) {
            root.navigate(Screen.SearchUsers.createRoute(query)) { launchSingleTop = true }
            assertEquals(Screen.SearchUsers.route + "?query={query}", root.currentDestination?.route)
            assertEquals(query, root.currentBackStackEntry?.arguments?.getString("query"))
            assertTrue(root.popBackStack())
            assertEquals(Screen.Home.route, root.currentDestination?.route)
        }
    }

    @Test fun returningFromARelatedSearchKeepsTheNestedVideosEntry() {
        val root = root()
        val tabs = controller().apply {
            graph = createGraph(startDestination = Screen.Videos.route) {
                composable(Screen.Videos.route) { }
            }
        }
        val entry = tabs.currentBackStackEntry
        root.navigate(Screen.SearchUsers.createRoute("Kirito"))
        assertTrue(root.popBackStack())
        assertEquals(entry, tabs.currentBackStackEntry)
        assertEquals(Screen.Videos.route, tabs.currentDestination?.route)
    }
}
