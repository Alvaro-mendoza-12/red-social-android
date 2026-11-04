package conectate.com.presentation.profile

import android.util.Log
import androidx.navigation.NavController
import conectate.com.data.model.Notification
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import conectate.com.util.notificationDisplayType
import conectate.com.util.notificationExtra

/** Resolve before navigating so missing/unsupported content remains an informational notice. */
internal fun notificationDestination(notice: Notification): String? {
    fun extra(key: String) = notificationExtra(notice, key)
    val type = notificationDisplayType(notice)
    val target = notice.targetId?.trim()?.takeIf(String::isNotBlank)
    val actor = notice.actorId.trim().takeIf(String::isNotBlank)
    val comment = extra("comment_id") ?: extra("focus_comment_id")
    val message = extra("message_id") ?: extra("focus_message_id")
    val group = extra("group_id")
    val community = extra("community_id")
    val video = extra("video_id") ?: target
    val post = extra("post_id") ?: target
    val moment = extra("moment_id") ?: target
    fun contentDestination(): String? = when (notice.targetType?.trim()?.lowercase()) {
        "video" -> video?.let { Screen.VideoPlayer.createRoute(it, focusCommentId = comment) }
        "post" -> post?.let { Screen.PostDetail.createRoute(it, focusCommentId = comment) }
        "moment" -> moment?.let(Screen.MomentViewer::createRoute)
        "profile" -> target?.let(Screen.UserProfile::createRoute)
        else -> null
    }
    return when (type) {
        "gaming_plan" -> target?.takeIf { conectate.com.presentation.purpose.purposeUuid(it) }?.let(Screen.GamePlan::createRoute)
        "birthday" -> (extra("birthday_user_id") ?: actor ?: target)?.let { Screen.Chat.createRoute(it, birthdayGreeting = true) }
        "follow", "friend_request_accepted", "voice_access_request" -> actor?.let(Screen.UserProfile::createRoute)
        "friend_request" -> Screen.FriendRequests.route
        "mention" -> if (notice.targetType.equals("video", true) || extra("video_id") != null)
            video?.let { Screen.VideoPlayer.createRoute(it, focusCommentId = comment) }
            else post?.let { Screen.PostDetail.createRoute(it, focusCommentId = comment) }
        "like_video", "comment_video", "reply_video", "new_video", "mention_video", "mention_video_description", "share_video", "admin_warning_video" ->
            video?.let { Screen.VideoPlayer.createRoute(it, focusCommentId = comment) }
        "like", "like_post", "comment", "comment_post", "reply_post", "new_post", "share", "share_post", "mention_post" ->
            post?.let { Screen.PostDetail.createRoute(it, focusCommentId = comment) }
        "message", "new_message" -> when {
            community != null -> Screen.CommunityChat.createRoute(community, focusMessageId = message)
            group != null -> Screen.GroupChat.createRoute(group, focusMessageId = message)
            target != null -> Screen.Chat.createRoute(target, focusMessageId = message)
            actor != null -> Screen.Chat.createRoute(actor, focusMessageId = message)
            else -> null
        }
        "group_member_added" -> (group ?: target)?.let { Screen.GroupChat.createRoute(it) }
        "community_member_added" -> (community ?: target)?.let { Screen.CommunityChat.createRoute(it) }
        "view_moment", "new_moment", "like_moment" -> moment?.let(Screen.MomentViewer::createRoute)
        "live_started", "new_live" -> (extra("live_id") ?: target)?.let(Screen.LiveViewer::createRoute)
            ?: Screen.LiveDiscovery.route
        "voice_invite", "voice_return" -> (extra("room_id") ?: target)?.let(Screen.RoomDetail::createRoute)
        "admin_access_audit" -> (extra("target_user_id") ?: target)?.let(Screen.UserProfile::createRoute)
        "admin_access_changed" -> Screen.AdminPanel.route
        "challenge_invite" -> Screen.Home.roundsRoute()
        "account_suspended", "account_enabled" -> notice.recipientId.trim().takeIf(String::isNotBlank)
            ?.let(Screen.UserProfile::createRoute)
        "notification_digest" -> Screen.NotificationSettings.route
        else -> contentDestination()
    }
}

fun handleNotificationNavigation(navController: NavController, notice: Notification) {
    val destination = notificationDestination(notice)
    if (destination == null) {
        AppFeedbackBus.show("Este aviso no incluye contenido para abrir.")
        return
    }
    try {
        navController.navigate(destination) { launchSingleTop = true }
    } catch (error: IllegalArgumentException) {
        Log.w("NotificationNav", "Notification destination is not available", error)
        AppFeedbackBus.showError("No se pudo abrir este contenido. Inténtalo de nuevo desde su sección.")
    }
}
