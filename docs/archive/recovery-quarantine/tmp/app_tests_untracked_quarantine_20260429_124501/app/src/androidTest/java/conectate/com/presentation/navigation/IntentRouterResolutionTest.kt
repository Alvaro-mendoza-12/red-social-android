package conectate.com.presentation.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.data.model.StoredAccountSession
import conectate.com.util.TrustedNavigationIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class IntentRouterResolutionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun newMessageNotification_resolvesCommunityChatRoute() {
        val intent = Intent().apply {
            putExtra("type", "new_message")
            putExtra("community_id", "community-1")
            putExtra("focus_message_id", "message-99")
        }
        TrustedNavigationIntents.markTrusted(context, intent)

        val decision = IntentRouter.resolveNotificationDecision(context, intent)
        assertEquals(
            IntentRouteDecision.Navigate(
                route = Screen.CommunityChat.createRoute("community-1", "message-99")
            ),
            decision
        )
    }

    @Test
    fun callInvite_requiresTrustAndBuildsInviteWhenSigned() {
        val rawIntent = Intent().apply {
            putExtra("type", "call_invite")
            putExtra("call_id", "call-1")
            putExtra("sender_id", "user-9")
            putExtra("sender_name", "Carlos")
            putExtra("call_context_type", "group")
            putExtra("call_context_id", "group-77")
        }

        assertEquals(IntentRouteDecision.Reject, IntentRouter.resolveNotificationDecision(context, rawIntent))

        TrustedNavigationIntents.markTrusted(context, rawIntent)
        val trustedDecision = IntentRouter.resolveNotificationDecision(context, rawIntent)
        assertTrue(trustedDecision is IntentRouteDecision.PresentCallInvite)
        val invite = (trustedDecision as IntentRouteDecision.PresentCallInvite).invite
        assertEquals("call-1", invite.callId)
        assertEquals("group", invite.contextType)
        assertEquals("group-77", invite.contextId)
    }

    @Test
    fun deepLinksAndRecovery_resolveToExpectedDestinations() {
        val postIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://conectate.com/post/post-88"))
        val postDecision = IntentRouter.resolveUriDecision(postIntent)
        assertEquals(IntentRouteDecision.Navigate(Screen.PostDetail.createRoute("post-88")), postDecision)

        val profileIntent = Intent(Intent.ACTION_VIEW, Uri.parse("conectate://profile/user-44"))
        val profileDecision = IntentRouter.resolveUriDecision(profileIntent)
        assertEquals(
            IntentRouteDecision.Navigate(
                Screen.QrAction.createRoute("conectate://profile/user-44", source = "deeplink")
            ),
            profileDecision
        )

        val webLoginIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("conectate://web-login?pairId=pair-1&nonce=nonce-1")
        )
        val webLoginDecision = IntentRouter.resolveUriDecision(webLoginIntent)
        assertEquals(
            IntentRouteDecision.Navigate(
                Screen.QrAction.createRoute(
                    "conectate://web-login?pairId=pair-1&nonce=nonce-1",
                    source = "deeplink"
                )
            ),
            webLoginDecision
        )

        val recoveryIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("conectate://recovery#type=recovery&access_token=access-1&refresh_token=refresh-1")
        )
        val recoveryDecision = IntentRouter.resolveUriDecision(recoveryIntent)
        assertEquals(
            IntentRouteDecision.ImportRecoverySession("access-1", "refresh-1"),
            recoveryDecision
        )
    }

    @Test
    fun shareAndLiveReturn_resolveToStableRoutes() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Mira esto")
        }
        val shareDecision = IntentRouter.resolveShareDecision(shareIntent)
        assertEquals(
            IntentRouteDecision.Navigate(Screen.Home.createRoute(startTab = "social", sharedText = "Mira esto")),
            shareDecision
        )

        val liveIntent = Intent().apply {
            putExtra("type", "live_return")
            putExtra("live_id", "live-44")
            putExtra("live_role", "host")
        }
        TrustedNavigationIntents.markTrusted(context, liveIntent)
        val liveDecision = IntentRouter.resolveNotificationDecision(context, liveIntent)
        assertEquals(
            IntentRouteDecision.Navigate(
                route = Screen.LiveHost.createRoute("live-44"),
                launchSingleTop = true
            ),
            liveDecision
        )
    }

    @Test
    fun newMessageForDifferentAccount_resolvesBridgeRouteInsteadOfBlindOpen() {
        val intent = Intent().apply {
            putExtra("type", "new_message")
            putExtra("chat_id", "chat-44")
            putExtra("sender_id", "user-77")
            putExtra("sender_name", "Conectate")
            putExtra("notification_preview", "Te escribieron desde otra cuenta")
            putExtra("recipient_id", "target-user")
            putExtra("recipient_display_name", "Cuenta de Conectate")
        }
        TrustedNavigationIntents.markTrusted(context, intent)

        val decision = IntentRouter.resolveNotificationDecision(
            context = context,
            incomingIntent = intent,
            currentUserId = "active-user",
            currentUserEmail = "alvaro@conectate.com",
            storedAccounts = listOf(
                StoredAccountSession(
                    id = "saved-target-account",
                    user_id = "target-user",
                    email = "conectate@conectate.com",
                    access_token = "token-a",
                    refresh_token = "refresh-a",
                    display_name = "Cuenta de Conectate"
                )
            )
        )

        assertEquals(
            IntentRouteDecision.Navigate(
                route = Screen.NotificationAccountBridge.createRoute(
                    route = Screen.Chat.createRoute(
                        userId = "user-77",
                        chatId = "chat-44"
                    ),
                    accountId = "saved-target-account",
                    accountLabel = "Cuenta de Conectate",
                    currentAccountLabel = "alvaro",
                    contextTitle = "Conectate",
                    preview = "Te escribieron desde otra cuenta"
                ),
                launchSingleTop = true
            ),
            decision
        )
    }
}
