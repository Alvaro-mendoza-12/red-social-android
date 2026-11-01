package conectate.com.presentation.messages

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.navigation.addAuthRoutes
import conectate.com.presentation.navigation.addMainRoutes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CollectionCreationNavigationTest {
    private fun controller(): NavHostController {
        val nav = NavHostController(RuntimeEnvironment.getApplication())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.navigatorProvider.addNavigator(DialogNavigator())
        nav.setViewModelStore(ViewModelStore())
        nav.graph = nav.createGraph(startDestination = Screen.Splash.route) {
            addAuthRoutes(nav, isInitializing = false, isLoggedIn = true, hasPendingIntent = false)
            addMainRoutes(nav, isLoggedIn = true)
        }
        nav.navigate(Screen.Home.createRoute(startTab = Screen.Messages.route))
        return nav
    }

    @Test fun groupAcknowledgementOpensTheActualRoomAndBackDoesNotReturnToTheForm() {
        val nav = controller()
        nav.navigate(Screen.CreateGroup.route)
        nav.openCreatedCollection("group", "11111111-1111-4111-8111-111111111111")
        assertEquals(Screen.GroupChat.route, nav.currentDestination?.route)
        assertEquals("11111111-1111-4111-8111-111111111111", nav.currentBackStackEntry?.arguments?.getString("groupId"))
        assertTrue(nav.popBackStack())
        assertEquals(Screen.Home.route, nav.currentDestination?.route)
    }

    @Test fun communityRecoveryOpensItsOwnRoomAndRemovesTheCompletedForm() {
        val nav = controller()
        nav.navigate(Screen.CreateCommunity.route)
        nav.openCreatedCollection("community", "22222222-2222-4222-8222-222222222222")
        assertEquals(Screen.CommunityChat.route, nav.currentDestination?.route)
        assertEquals("22222222-2222-4222-8222-222222222222", nav.currentBackStackEntry?.arguments?.getString("communityId"))
        assertTrue(nav.popBackStack())
        assertEquals(Screen.Home.route, nav.currentDestination?.route)
    }
}
