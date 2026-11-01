package conectate.com.presentation.social

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.navigation.addAuthRoutes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChallengeNavigationTest {
    private fun rootController(): NavHostController {
        val nav = NavHostController(RuntimeEnvironment.getApplication())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.navigatorProvider.addNavigator(DialogNavigator())
        nav.setViewModelStore(ViewModelStore())
        nav.graph = nav.createGraph(startDestination = Screen.Splash.route) {
            addAuthRoutes(nav, isInitializing = true, isLoggedIn = true, hasPendingIntent = false)
        }
        return nav
    }

    @Test fun anInvitationResolvesAgainstTheActualRootGraphWithTheRoundsPage() {
        val nav = rootController()
        nav.navigate(Screen.Home.roundsRoute())
        assertEquals(Screen.Home.route, nav.currentDestination?.route)
        assertEquals(Screen.Social.route, nav.currentBackStackEntry?.arguments?.getString("startTab"))
        assertEquals(2, nav.currentBackStackEntry?.arguments?.getInt("socialPage"))
    }

    @Test fun existingHomeLinksWithoutTheNewArgumentKeepTheDefaultPage() {
        val nav = rootController()
        nav.navigate(Screen.Home.createRoute(startTab = Screen.Social.route))
        assertEquals(Screen.Home.route, nav.currentDestination?.route)
        assertEquals(0, nav.currentBackStackEntry?.arguments?.getInt("socialPage"))
    }

    @Test fun anInvitationAfterAnotherHomeTabStillResolvesItsOwnArguments() {
        val nav = rootController()
        nav.navigate(Screen.Home.createRoute(startTab = Screen.Messages.route))
        nav.navigate(Screen.Home.roundsRoute())
        assertEquals(Screen.Home.route, nav.currentDestination?.route)
        assertEquals("social", nav.currentBackStackEntry?.arguments?.getString("startTab"))
        assertEquals(2, nav.currentBackStackEntry?.arguments?.getInt("socialPage"))
    }
}
