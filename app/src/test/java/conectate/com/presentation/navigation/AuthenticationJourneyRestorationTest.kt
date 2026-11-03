package conectate.com.presentation.navigation

import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavController
import androidx.navigation.NavHostController
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
class AuthenticationJourneyRestorationTest {
    private fun controller(state: Bundle? = null) = NavHostController(ApplicationProvider.getApplicationContext()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        setViewModelStore(ViewModelStore())
        state?.getBundle("navigation")?.let(::restoreState)
        graph = createGraph(startDestination = Screen.Splash.route) {
            addMainRoutes(this@apply, isLoggedIn = true)
            addAuthRoutes(this@apply, isInitializing = false, isLoggedIn = true, hasPendingIntent = false)
        }
    }

    private fun NavHostController.save(process: String) = Bundle().also {
        it.putBundle("navigation", saveState())
        AuthenticationJourneyRestoration.save(it, AuthenticationJourneyRestoration.pending(this) == true, process)
    }

    @Test fun `registration privacy and shared content survive a different process as one journey`() {
        val nav = controller()
        PendingSharedContentDestination.remember("post", "post-original",
            nav.getBackStackEntry(nav.graph.id).savedStateHandle)
        nav.navigate(Screen.Login.route)
        nav.navigate(Screen.Register.route)
        nav.navigate(Screen.PrivacyPolicy.route)
        val state = nav.save("process-before")
        val accepted = AuthenticationJourneyRestoration.stateForCreate(state, "process-after")
        assertSame(state, accepted)
        val restored = controller(accepted)
        assertEquals(Screen.PrivacyPolicy.route, restored.currentDestination?.route)
        assertTrue(restored.popBackStack())
        assertEquals(Screen.Register.route, restored.currentDestination?.route)
        assertTrue(restored.openPendingSharedContent())
        assertEquals(Screen.PostDetail.route, restored.currentDestination?.route)
        assertEquals("post-original", restored.currentBackStackEntry?.arguments?.getString("postId"))
        assertNull(PendingSharedContentDestination.consume(restored))
        assertEquals(false, AuthenticationJourneyRestoration.pending(restored))
    }

    @Test fun `ordinary home keeps rotation state but a different process uses the cold start policy`() {
        val nav = controller().apply { navigate(Screen.Home.createRoute(startTab = "videos")) }
        val state = nav.save("same-process")
        assertSame(state, AuthenticationJourneyRestoration.stateForCreate(state, "same-process"))
        assertNull(AuthenticationJourneyRestoration.stateForCreate(state, "new-process"))
        assertNull(AuthenticationJourneyRestoration.stateForCreate(null, "new-process"))
        assertNull(AuthenticationJourneyRestoration.stateForCreate(Bundle(), "new-process"))
    }

    @Test fun `destination changes update preservation before another frame and clear it on completion`() {
        val nav = controller()
        var pending = false
        val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
            AuthenticationJourneyRestoration.pending(controller)?.let { pending = it }
        }
        nav.addOnDestinationChangedListener(listener)
        assertFalse(pending)
        nav.navigate(Screen.Register.route)
        assertTrue(pending)
        nav.navigate(Screen.PrivacyPolicy.route)
        assertTrue(pending)
        nav.navigate(Screen.Home.createRoute()) { popUpTo(nav.graph.id) { inclusive = false } }
        assertFalse(pending)
        assertNull(AuthenticationJourneyRestoration.stateForCreate(nav.save("before"), "after"))
        nav.removeOnDestinationChangedListener(listener)
    }

    @Test fun `a privacy page opened outside registration does not restore an old authenticated tab`() {
        val nav = controller().apply {
            navigate(Screen.Home.createRoute())
            navigate(Screen.PrivacyPolicy.route)
        }
        assertEquals(false, AuthenticationJourneyRestoration.pending(nav))
        assertNull(AuthenticationJourneyRestoration.stateForCreate(nav.save("before"), "after"))
    }

    @Test fun `unfinished login recovery and bridge are preserved while splash has not decided yet`() {
        val nav = controller()
        assertNull(AuthenticationJourneyRestoration.pending(nav))
        for (route in listOf(Screen.Login.route, Screen.AddAccountLogin.route,
            Screen.AccountRecoveryHelp.route, Screen.NotificationAccountBridge.createRoute(Screen.Notifications.route))) {
            nav.navigate(route)
            val state = nav.save("before")
            assertSame(state, AuthenticationJourneyRestoration.stateForCreate(state, "after"))
            assertTrue(AuthenticationJourneyRestoration.wasPending(state))
        }
    }
}
