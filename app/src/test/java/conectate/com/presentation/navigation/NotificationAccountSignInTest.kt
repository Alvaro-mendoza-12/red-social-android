package conectate.com.presentation.navigation

import androidx.navigation.NavHostController
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class NotificationAccountSignInTest {
    private fun controller(savedState: android.os.Bundle? = null) = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        setViewModelStore(ViewModelStore())
        if (savedState != null) restoreState(savedState)
        graph = createGraph(startDestination = Screen.Notifications.route) {
            addMainRoutes(this@apply, isLoggedIn = true)
            addAuthRoutes(this@apply, isInitializing = false, isLoggedIn = true, hasPendingIntent = false)
        }
    }

    private fun NavHostController.bridge() = apply {
        navigate(Screen.NotificationAccountBridge.createRoute(
            destinationRoute = Screen.PostDetail.createRoute("post-original"),
            targetUserId = "recipient", targetEmail = "recipient@example.test", targetAccountLabel = "Recipiente",
        ))
    }.currentBackStackEntry!!

    @Test fun `login returns to the same bridge with the original content and recipient`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        assertEquals(Screen.AddAccountLogin.route, nav.currentDestination?.route)
        assertTrue(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertEquals(bridge.id, nav.currentBackStackEntry?.id)
        assertEquals(Screen.PostDetail.createRoute("post-original"), bridge.arguments?.getString("destination"))
        assertEquals("recipient", bridge.arguments?.getString("targetUserId"))
        assertEquals("recipient@example.test", bridge.arguments?.getString("targetEmail"))
        assertEquals("recipient", NotificationAccountSignIn.takeResult(bridge.savedStateHandle))
        assertNull(NotificationAccountSignIn.takeResult(bridge.savedStateHandle))
    }

    @Test fun `recovery inside the requested login also returns to its notification`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        nav.navigate(Screen.AccountRecoveryHelp.route)
        assertTrue(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertEquals(bridge.id, nav.currentBackStackEntry?.id)
    }

    @Test fun `an unrelated login cannot consume a notification left lower in the stack`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        assertTrue(nav.popBackStack())
        nav.navigate(Screen.AddAccountLogin.route)
        assertFalse(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertEquals(Screen.AddAccountLogin.route, nav.currentDestination?.route)
        assertNull(NotificationAccountSignIn.takeResult(bridge.savedStateHandle))
    }

    @Test fun `a normal add account login has no notification to return to`() {
        val nav = controller()
        nav.navigate(Screen.AddAccountLogin.route)
        assertFalse(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertEquals(Screen.AddAccountLogin.route, nav.currentDestination?.route)
    }

    @Test fun `restoring navigation during login retains its exact notification and account`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        val restored = controller(nav.saveState())
        assertEquals(Screen.AddAccountLogin.route, restored.currentDestination?.route)
        assertTrue(NotificationAccountSignIn.returnToBridge(restored, "recipient"))
        val entry = restored.currentBackStackEntry!!
        assertEquals(bridge.id, entry.id)
        assertEquals(Screen.PostDetail.createRoute("post-original"), entry.arguments?.getString("destination"))
        assertEquals("recipient", entry.arguments?.getString("targetUserId"))
        assertEquals("recipient", NotificationAccountSignIn.takeResult(entry.savedStateHandle))
    }

    @Test fun `a fresh manual login cannot be skipped by the existing expired account`() {
        val nav = controller()
        NotificationAccountSignIn.open(nav, nav.bridge(), "opaque-before")
        assertNull(NotificationAccountSignIn.restoredBridge(nav, nav.currentBackStackEntry!!.id))
        assertFalse(NotificationAccountSignIn.hasNewSession(nav, "opaque-before"))
    }

    @Test fun `a different process retains the exact bridge and requires a changed session`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge, "opaque-before")
        val restored = controller(nav.saveState())
        val pending = NotificationAccountSignIn.restoredBridge(restored, restored.currentBackStackEntry!!.id, "another-process")
        assertEquals(bridge.id, pending?.id)
        assertFalse(NotificationAccountSignIn.hasNewSession(restored, "opaque-before"))
        assertTrue(NotificationAccountSignIn.hasNewSession(restored, "opaque-after"))
        assertTrue(NotificationAccountSignIn.returnToBridge(restored, "recipient"))
        assertEquals(bridge.id, restored.currentBackStackEntry?.id)
        assertEquals("recipient", NotificationAccountSignIn.takeResult(restored.currentBackStackEntry!!.savedStateHandle))
    }

    @Test fun `restored recovery belongs to its underlying login and a different entry cannot resume it`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge, "opaque-before")
        val loginId = nav.currentBackStackEntry!!.id
        nav.navigate(Screen.AccountRecoveryHelp.route)
        val restored = controller(nav.saveState())
        assertNull(NotificationAccountSignIn.restoredBridge(restored, loginId, "another-process"))
        assertEquals(bridge.id, NotificationAccountSignIn.restoredBridge(restored,
            restored.currentBackStackEntry!!.id, "another-process")?.id)
    }

    @Test fun `an unrelated current screen cannot return through a login remaining below it`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        nav.navigate(Screen.Notifications.route)
        assertFalse(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertNull(NotificationAccountSignIn.takeResult(bridge.savedStateHandle))
        assertEquals(Screen.Notifications.route, nav.currentDestination?.route)
    }

    @Test fun `a new bridge cannot borrow a login from an older bridge instance`() {
        val nav = controller()
        val original = nav.bridge()
        NotificationAccountSignIn.open(nav, original, "opaque-before")
        val newer = nav.bridge()
        nav.navigate(Screen.AccountRecoveryHelp.route)
        assertNull(NotificationAccountSignIn.restoredBridge(nav, nav.currentBackStackEntry!!.id, "another-process"))
        assertFalse(NotificationAccountSignIn.returnToBridge(nav, "recipient"))
        assertNull(NotificationAccountSignIn.takeResult(original.savedStateHandle))
        assertNull(NotificationAccountSignIn.takeResult(newer.savedStateHandle))
    }

    @Test fun `a legacy login without a session fingerprint keeps the manual recovery path`() {
        val nav = controller()
        val bridge = nav.bridge()
        NotificationAccountSignIn.open(nav, bridge)
        val restored = controller(nav.saveState())
        assertFalse(NotificationAccountSignIn.hasNewSession(restored, "opaque-current"))
        assertTrue(NotificationAccountSignIn.returnToBridge(restored, "recipient"))
    }
}
