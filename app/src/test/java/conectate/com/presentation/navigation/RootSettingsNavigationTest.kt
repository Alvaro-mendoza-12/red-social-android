package conectate.com.presentation.navigation

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class RootSettingsNavigationTest {
    @Test fun settingsOpenedOutsideHomeCanOpenItsSharedDestinationsAndReturn() {
        val controller = NavHostController(ApplicationProvider.getApplicationContext())
        controller.navigatorProvider.addNavigator(ComposeNavigator())
        controller.navigatorProvider.addNavigator(DialogNavigator())
        controller.graph = controller.createGraph(startDestination = Screen.Settings.route) {
            addMainRoutes(controller, isLoggedIn = true)
        }
        listOf(Screen.LanguageSettings, Screen.HelpSettings, Screen.TermsAndConditions).forEach { destination ->
            controller.navigate(destination.route)
            assertEquals(destination.route, controller.currentDestination?.route)
            assertTrue(controller.popBackStack())
            assertEquals(Screen.Settings.route, controller.currentDestination?.route)
        }
    }
}
