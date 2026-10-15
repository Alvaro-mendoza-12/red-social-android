package conectate.com.presentation.navigation

import android.content.Intent
import android.net.Uri
import androidx.navigation.NavHostController
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.presentation.messages.hasCapturedIncomingShare
import conectate.com.presentation.messages.incomingShareText
import conectate.com.presentation.messages.incomingShareUris
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.util.notificationAccountTargetFromIntent
import conectate.com.util.resolveStoredAccountForTarget
import conectate.com.util.storedAccountDisplayLabel
import conectate.com.util.TrustedNavigationIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal fun videoResumeMsFromLink(uri: Uri): Long = uri.getQueryParameter("resumeMs")
    ?.toLongOrNull()?.takeIf { it in 0L..86_400_000L } ?: 0L

internal fun videoClipEndMsFromLink(uri: Uri): Long {
    val start = videoResumeMsFromLink(uri)
    val end = uri.getQueryParameter("endMs")?.toLongOrNull() ?: return 0L
    return end.takeIf { it > start && end <= 86_400_000L &&
        end - start in 5_000L..60_000L } ?: 0L
}

object IntentRouter {

    fun hasPendingNavigationIntent(pendingIntent: Intent?): Boolean {
        pendingIntent ?: return false
        val data = pendingIntent.data
        val isRecovery = isTrustedRecoveryOrigin(data?.scheme, data?.host)
        val isDeepLink = data != null && isTrustedExternalDeepLinkOrigin(data.scheme, data.host)
        val isShareAction = pendingIntent.action == Intent.ACTION_SEND ||
            pendingIntent.action == Intent.ACTION_SEND_MULTIPLE
        val isShare = isShareAction && (
            hasCapturedIncomingShare(pendingIntent) ||
                !incomingShareText(pendingIntent).isNullOrBlank() ||
                incomingShareUris(pendingIntent).isNotEmpty()
            )
        val hasPushType = !pendingIntent.getStringExtra("type").isNullOrBlank()
        return isRecovery || isDeepLink || isShare || hasPushType
    }

    fun handleIntentNavigation(
        incomingIntent: Intent,
        navController: NavHostController,
        authRepository: AuthRepository,
        notificationRepository: NotificationRepository,
        activityScope: CoroutineScope,
        clearIntent: () -> Unit
    ): Boolean {
        if (handleNotificationIntent(incomingIntent, navController, authRepository, clearIntent)) {
            val notificationId = incomingIntent.getStringExtra("notification_id")?.trim().orEmpty()
            if (notificationId.isNotBlank() &&
                TrustedNavigationIntents.isTrusted(navController.context.applicationContext, incomingIntent)) {
                val target = notificationAccountTargetFromIntent(incomingIntent)
                activityScope.launch {
                    val matchingSession = withTimeoutOrNull(120_000L) {
                        authRepository.getAuthStateFlow().first { session ->
                            val user = session?.user
                            user?.id?.isNotBlank() == true &&
                                (target == null || target.matches(user.id, user.email))
                        }
                    }
                    if (matchingSession != null) notificationRepository.markAsRead(notificationId)
                }
            }
            return true
        }
        if (consumeExternalNavigation(clearIntent, { AppFeedbackBus.show("No se pudo abrir el enlace. Revisa que sea válido.") }) {
                handleUriIntent(incomingIntent, navController, clearIntent)
            }) return true
        if (handleIncomingShareIntent(incomingIntent, navController, clearIntent)) return true
        return false
    }

    private fun handleNotificationIntent(
        incomingIntent: Intent,
        navController: NavHostController,
        authRepository: AuthRepository,
        clearIntent: () -> Unit
    ): Boolean {
        val type = incomingIntent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (type.isBlank()) return false

        // Push payloads are converted into an app-owned PendingIntent before reaching
        // this router. Do not let another app forge an internal notification action.
        if (
            TrustedNavigationIntents.requiresTrust(type) &&
                !TrustedNavigationIntents.isTrusted(navController.context.applicationContext, incomingIntent)
        ) {
            android.util.Log.w("IntentRouter", "Rejected untrusted notification intent type=$type")
            clearIntent()
            return true
        }

        when (type) {
            "new_message" -> {
                val chatId = incomingIntent.getStringExtra("chat_id")
                    ?: incomingIntent.getStringExtra("target_id")
                val senderId = incomingIntent.getStringExtra("sender_id")
                val messageId = incomingIntent.getStringExtra("message_id")
                    ?: incomingIntent.getStringExtra("focus_message_id")
                val targetType = incomingIntent.getStringExtra("target_type")?.trim()?.lowercase()
                val groupId = incomingIntent.getStringExtra("group_id")
                val communityId = incomingIntent.getStringExtra("community_id")
                val isGroupFromString = incomingIntent.getStringExtra("is_group")
                    ?.trim()?.equals("true", ignoreCase = true) == true
                val isCommunityFromString = incomingIntent.getStringExtra("is_community")
                    ?.trim()?.equals("true", ignoreCase = true) == true
                val isGroup = incomingIntent.getBooleanExtra("is_group", false) ||
                    isGroupFromString ||
                    targetType == "group" ||
                    !groupId.isNullOrBlank()
                val isCommunity = incomingIntent.getBooleanExtra("is_community", false) ||
                    isCommunityFromString ||
                    targetType == "community" ||
                    !communityId.isNullOrBlank()

                val targetGroupId = groupId ?: if (isGroup && !isCommunity) chatId else null
                val targetCommunityId = communityId ?: if (isCommunity) chatId else null

                android.util.Log.d(
                    "MainActivity",
                    "Navigating via Notification: chatId=$chatId, groupId=$targetGroupId, communityId=$targetCommunityId, targetType=$targetType"
                )

                when {
                    !targetCommunityId.isNullOrBlank() -> {
                        navigateNotificationDestination(
                            incomingIntent = incomingIntent,
                            navController = navController,
                            authRepository = authRepository,
                            destinationRoute = Screen.CommunityChat.createRoute(
                                targetCommunityId,
                                focusMessageId = messageId
                            )
                        )
                        clearIntent()
                        return true
                    }
                    !targetGroupId.isNullOrBlank() -> {
                        navigateNotificationDestination(
                            incomingIntent = incomingIntent,
                            navController = navController,
                            authRepository = authRepository,
                            destinationRoute = Screen.GroupChat.createRoute(
                                targetGroupId,
                                focusMessageId = messageId
                            )
                        )
                        clearIntent()
                        return true
                    }
                    !chatId.isNullOrBlank() || !senderId.isNullOrBlank() -> {
                        val targetUserOrChat = senderId ?: chatId ?: return false
                        navigateNotificationDestination(
                            incomingIntent = incomingIntent,
                            navController = navController,
                            authRepository = authRepository,
                            destinationRoute = Screen.Chat.createRoute(
                                targetUserOrChat,
                                focusMessageId = messageId
                            )
                        )
                        clearIntent()
                        return true
                    }
                }
            }

            "like", "like_post", "comment", "comment_post", "reply_post", "share", "share_post" -> {
                val postId = incomingIntent.getStringExtra("post_id") ?: incomingIntent.getStringExtra("target_id")
                val commentId = incomingIntent.getStringExtra("comment_id")
                    ?: incomingIntent.getStringExtra("focus_comment_id")
                if (!postId.isNullOrBlank()) {
                    navigateNotificationDestination(
                        incomingIntent = incomingIntent,
                        navController = navController,
                        authRepository = authRepository,
                        destinationRoute = Screen.PostDetail.createRoute(
                            postId,
                            focusCommentId = commentId
                        )
                    )
                    clearIntent()
                    return true
                }
            }

            "mention", "mention_post" -> {
                val targetType = incomingIntent.getStringExtra("target_type")?.trim()?.lowercase()
                val commentId = incomingIntent.getStringExtra("comment_id")
                    ?: incomingIntent.getStringExtra("focus_comment_id")
                val videoId = incomingIntent.getStringExtra("video_id")
                val postId = incomingIntent.getStringExtra("post_id")
                if (type == "mention" && (targetType == "video" || !videoId.isNullOrBlank())) {
                    val resolvedVideo = videoId ?: incomingIntent.getStringExtra("target_id")
                    if (!resolvedVideo.isNullOrBlank()) {
                        navigateNotificationDestination(
                            incomingIntent = incomingIntent,
                            navController = navController,
                            authRepository = authRepository,
                            destinationRoute = Screen.VideoPlayer.createRoute(
                                resolvedVideo,
                                focusCommentId = commentId
                            )
                        )
                        clearIntent()
                        return true
                    }
                } else {
                    val resolvedPost = postId ?: incomingIntent.getStringExtra("target_id")
                    if (!resolvedPost.isNullOrBlank()) {
                        navigateNotificationDestination(
                            incomingIntent = incomingIntent,
                            navController = navController,
                            authRepository = authRepository,
                            destinationRoute = Screen.PostDetail.createRoute(
                                resolvedPost,
                                focusCommentId = commentId
                            )
                        )
                        clearIntent()
                        return true
                    }
                }
            }

            "like_video", "comment_video", "reply_video", "share_video", "admin_warning_video", "mention_video" -> {
                val videoId = incomingIntent.getStringExtra("video_id") ?: incomingIntent.getStringExtra("target_id")
                val commentId = incomingIntent.getStringExtra("comment_id")
                    ?: incomingIntent.getStringExtra("focus_comment_id")
                if (!videoId.isNullOrBlank()) {
                    navigateNotificationDestination(
                        incomingIntent = incomingIntent,
                        navController = navController,
                        authRepository = authRepository,
                        destinationRoute = Screen.VideoPlayer.createRoute(
                            videoId,
                            focusCommentId = commentId
                        )
                    )
                    clearIntent()
                    return true
                }
            }

            "new_moment", "view_moment" -> {
                val momentId = incomingIntent.getStringExtra("moment_id")
                    ?: incomingIntent.getStringExtra("target_id")
                if (!momentId.isNullOrBlank()) {
                    navigateNotificationDestination(
                        incomingIntent,
                        navController,
                        authRepository,
                        Screen.MomentViewer.createRoute(momentId)
                    )
                    clearIntent()
                    return true
                }
            }

            "follow", "friend_request", "voice_access_request", "birthday" -> {
                val followerId = incomingIntent.getStringExtra("follower_id")
                    ?: incomingIntent.getStringExtra("target_id")
                if (!followerId.isNullOrBlank()) {
                    navigateNotificationDestination(
                        incomingIntent,
                        navController,
                        authRepository,
                        Screen.UserProfile.createRoute(followerId)
                    )
                    clearIntent()
                    return true
                }
            }

            "voice_invite", "voice_return" -> {
                val roomId = incomingIntent.getStringExtra("room_id")
                    ?: incomingIntent.getStringExtra("target_id")
                if (!roomId.isNullOrBlank()) {
                    navigateNotificationDestination(
                        incomingIntent,
                        navController,
                        authRepository,
                        Screen.RoomDetail.createRoute(roomId)
                    )
                    clearIntent()
                    return true
                }
            }

            "call_return" -> {
                val destination = resolveCallReturnRoute(incomingIntent.getStringExtra("call_id"))
                if (destination != null) {
                    navigateNotificationDestination(incomingIntent, navController, authRepository,
                        destination, launchSingleTop = true)
                }
                clearIntent()
                return true
            }

            "live_return" -> {
                val destination = resolveLiveReturnRoute(
                    liveId = incomingIntent.getStringExtra("live_id")
                        ?: incomingIntent.getStringExtra("target_id"),
                    role = incomingIntent.getStringExtra("live_role")
                )
                if (destination != null) {
                    navigateNotificationDestination(
                        incomingIntent,
                        navController,
                        authRepository,
                        destination,
                        launchSingleTop = true
                    )
                    clearIntent()
                    return true
                }
            }

            "live_started" -> {
                // The host role is intentionally never accepted from a remote notification.
                // Opening the viewer validates the live and the user's access again server-side.
                val destination = resolveLiveStartedRoute(
                    incomingIntent.getStringExtra("live_id")
                        ?: incomingIntent.getStringExtra("target_id")
                ) ?: Screen.LiveDiscovery.route
                navigateNotificationDestination(
                    incomingIntent,
                    navController,
                    authRepository,
                    destination,
                    launchSingleTop = true
                )
                clearIntent()
                return true
            }

            "live_discovery" -> {
                navigateNotificationDestination(
                    incomingIntent,
                    navController,
                    authRepository,
                    Screen.LiveDiscovery.route,
                    launchSingleTop = true
                )
                clearIntent()
                return true
            }

            "challenge_invite" -> {
                navigateNotificationDestination(
                    incomingIntent,
                    navController,
                    authRepository,
                    Screen.Home.createRoute(startTab = Screen.Social.route)
                )
                clearIntent()
                return true
            }
        }

        return false
    }

    private fun navigateNotificationDestination(
        incomingIntent: Intent,
        navController: NavHostController,
        authRepository: AuthRepository,
        destinationRoute: String,
        launchSingleTop: Boolean = false
    ) {
        val target = notificationAccountTargetFromIntent(incomingIntent)
        val sessionUser = authRepository.getAuthStateFlow().value?.user
        if (target == null || target.matches(sessionUser?.id, sessionUser?.email)) {
            navController.navigate(destinationRoute) {
                this.launchSingleTop = launchSingleTop
            }
            return
        }

        val accounts = authRepository.storedAccounts.value
        val storedTarget = resolveStoredAccountForTarget(target, accounts)
        val currentAccount = accounts.firstOrNull { account ->
            account.user_id?.equals(sessionUser?.id, ignoreCase = true) == true ||
                account.email.equals(sessionUser?.email, ignoreCase = true)
        }
        val targetLabel = target.presentableLabel(storedTarget?.let(::storedAccountDisplayLabel))
            ?: "otra cuenta"
        val currentLabel = currentAccount?.let(::storedAccountDisplayLabel)
            ?: sessionUser?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: "tu cuenta actual"
        val contextTitle = listOf(
            incomingIntent.getStringExtra("group_name"),
            incomingIntent.getStringExtra("community_name"),
            incomingIntent.getStringExtra("sender_name"),
            incomingIntent.getStringExtra("title")
        ).firstOrNull { !it.isNullOrBlank() }
        val preview = listOf(
            incomingIntent.getStringExtra("content"),
            incomingIntent.getStringExtra("message"),
            incomingIntent.getStringExtra("body"),
            incomingIntent.getStringExtra("text")
        ).firstOrNull { !it.isNullOrBlank() }

        navController.navigate(
            Screen.NotificationAccountBridge.createRoute(
                destinationRoute = destinationRoute,
                targetAccountId = storedTarget?.id,
                targetAccountLabel = targetLabel,
                currentAccountLabel = currentLabel,
                contextTitle = contextTitle,
                preview = preview
            )
        ) {
            this.launchSingleTop = true
        }
    }

    private fun handleUriIntent(
        incomingIntent: Intent,
        navController: NavHostController,
        clearIntent: () -> Unit
    ): Boolean {
        val uri = incomingIntent.data ?: return false
        if (!isTrustedExternalDeepLinkOrigin(uri.scheme, uri.host)) {
            android.util.Log.w("IntentRouter", "Rejected untrusted deep-link origin")
            clearIntent()
            return true
        }
        val surveyLink = (uri.scheme == "conectate" && uri.host == "survey") ||
            (uri.scheme == "https" && uri.pathSegments.firstOrNull() == "survey")
        if (surveyLink) {
            val id = safeExternalRouteValue(if (uri.scheme == "conectate") uri.pathSegments.firstOrNull() else uri.pathSegments.getOrNull(1))
            if (id != null && id.matches(Regex("[0-9a-fA-F-]{36}"))) {
                navController.navigate(Screen.Surveys.createRoute(id, safeExternalRouteValue(uri.getQueryParameter("key"), 100)))
                clearIntent()
                return true
            }
        }
        // Never log the full URI: recovery links carry access and refresh
        // credentials in their fragment.
        android.util.Log.d(
            "IntentRouter",
            "Handling deep link scheme=${uri.scheme} host=${uri.host} path=${uri.path.orEmpty().take(120)}"
        )

        val isWebLoginQr =
            (uri.scheme == "conectate" && uri.host == "web-login") ||
                (
                    (uri.scheme == "https" || uri.scheme == "http") &&
                        (uri.host == "conectate.com" || uri.host == "www.conectate.com") &&
                        uri.pathSegments.firstOrNull() == "web-login"
                    )
        if (isWebLoginQr) {
            val pairId = safeExternalRouteValue(uri.getQueryParameter("pairId"), 160)
            val nonce = safeExternalRouteValue(uri.getQueryParameter("nonce"), 512)
            navController.navigate(Screen.WebLoginScanner.createRoute(pairId = pairId, nonce = nonce))
            clearIntent()
            return true
        }

        if ((uri.scheme == "https" || uri.scheme == "http") && (uri.host == "conectate.com" || uri.host == "www.conectate.com")) {
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                when (pathSegments[0]) {
                    "post" -> {
                        val postId = safeExternalRouteId(pathSegments.getOrNull(1))
                        if (postId != null) {
                            navController.navigate(Screen.PostDetail.createRoute(postId))
                            clearIntent()
                            return true
                        }
                    }
                    "video" -> {
                        val videoId = safeExternalRouteId(pathSegments.getOrNull(1))
                        if (videoId != null) {
                            navController.navigate(Screen.VideoPlayer.createRoute(
                                videoId, resumeMs = videoResumeMsFromLink(uri),
                                endMs = videoClipEndMsFromLink(uri)))
                            clearIntent()
                            return true
                        }
                    }
                    "profile", "u" -> {
                        val userId = safeExternalRouteId(pathSegments.getOrNull(1))
                        if (userId != null) {
                            navController.navigate(Screen.UserProfile.createRoute(userId))
                            clearIntent()
                            return true
                        }
                    }
                    "group" -> {
                        val groupId = safeExternalRouteId(pathSegments.getOrNull(1))
                        if (groupId != null) {
                            navController.navigate(
                                Screen.GroupDetail.createRoute(
                                    groupId,
                                    safeExternalRouteValue(uri.getQueryParameter("invite"), 256)
                                )
                            )
                            clearIntent()
                            return true
                        }
                    }
                    "community" -> {
                        val communityId = safeExternalRouteId(pathSegments.getOrNull(1))
                        if (communityId != null) {
                            navController.navigate(Screen.CommunityDetail.createRoute(communityId))
                            clearIntent()
                            return true
                        }
                    }
                }
            }
        } else if (uri.scheme == "conectate") {
            when (uri.host) {
                "videos" -> {
                    navController.navigate(Screen.Home.createRoute(startTab = Screen.Videos.route))
                    clearIntent()
                    return true
                }
                "social" -> {
                    navController.navigate(Screen.Home.createRoute(startTab = Screen.Social.route))
                    clearIntent()
                    return true
                }
                "messages" -> {
                    navController.navigate(Screen.Home.createRoute(startTab = Screen.Messages.route))
                    clearIntent()
                    return true
                }
                "create-moment" -> {
                    navController.navigate(Screen.CreateMoment.route)
                    clearIntent()
                    return true
                }
                "post" -> {
                    safeExternalRouteId(uri.pathSegments.firstOrNull())?.let { id ->
                        navController.navigate(Screen.PostDetail.createRoute(id))
                        clearIntent()
                        return true
                    }
                }
                "video" -> {
                    safeExternalRouteId(uri.pathSegments.firstOrNull())?.let { id ->
                        navController.navigate(Screen.VideoPlayer.createRoute(
                            id, resumeMs = videoResumeMsFromLink(uri),
                            endMs = videoClipEndMsFromLink(uri)))
                        clearIntent()
                        return true
                    }
                }
                "profile" -> {
                    safeExternalRouteId(uri.pathSegments.firstOrNull())?.let { id ->
                        navController.navigate(Screen.UserProfile.createRoute(id))
                        clearIntent()
                        return true
                    }
                }
                "group" -> {
                    safeExternalRouteId(uri.pathSegments.firstOrNull())?.let { id ->
                        navController.navigate(
                            Screen.GroupDetail.createRoute(
                                id,
                                safeExternalRouteValue(uri.getQueryParameter("invite"), 256)
                            )
                        )
                        clearIntent()
                        return true
                    }
                }
                "community" -> {
                    safeExternalRouteId(uri.pathSegments.firstOrNull())?.let { id ->
                        navController.navigate(Screen.CommunityDetail.createRoute(id))
                        clearIntent()
                        return true
                    }
                }
            }
        }

        val isRecovery = isTrustedRecoveryOrigin(uri.scheme, uri.host)
        if (!isRecovery) {
            clearIntent()
            AppFeedbackBus.show("El enlace no contiene un destino válido.")
            return true
        }

        // Old custom-scheme recovery links can be intercepted by another app.
        // Never import access or refresh tokens delivered through this route.
        android.util.Log.d("MainActivity", "Legacy recovery link received")
        clearIntent()
        AppFeedbackBus.show("Solicita un código nuevo desde Recuperar cuenta.")
        navController.navigate(Screen.Login.route)
        return true
    }

    private fun handleIncomingShareIntent(
        incomingIntent: Intent,
        navController: NavHostController,
        clearIntent: () -> Unit
    ): Boolean {
        if (incomingIntent.action != Intent.ACTION_SEND && incomingIntent.action != Intent.ACTION_SEND_MULTIPLE) {
            return false
        }

        val sharedText = incomingShareText(incomingIntent)
        val sharedUris = incomingShareUris(incomingIntent)
        if (sharedText == null && sharedUris.isEmpty() && !hasCapturedIncomingShare(incomingIntent)) return false

        android.util.Log.d(
            "MainActivity",
            "Incoming share detected: action=${incomingIntent.action}, itemCount=${sharedUris.size}, hasText=${sharedText != null}"
        )
        navController.navigate(
            Screen.Home.createRoute(
                startTab = Screen.Messages.route
            )
        ) { launchSingleTop = true }
        clearIntent()
        return true
    }
}

internal fun resolveLiveReturnRoute(liveId: String?, role: String?): String? {
    val normalizedLiveId = liveId?.trim().orEmpty()
    if (normalizedLiveId.isBlank()) return null
    return when (role?.trim()?.lowercase()) {
        "host" -> Screen.LiveHost.createRoute(normalizedLiveId)
        "viewer" -> Screen.LiveViewer.createRoute(normalizedLiveId)
        else -> null
    }
}

internal fun resolveLiveStartedRoute(liveId: String?): String? {
    val normalizedLiveId = liveId?.trim().orEmpty()
    if (normalizedLiveId.isBlank()) return null
    return Screen.LiveViewer.createRoute(normalizedLiveId)
}

internal fun isTrustedRecoveryOrigin(scheme: String?, host: String?): Boolean =
    scheme.equals("conectate", ignoreCase = true) && host.equals("recovery", ignoreCase = true)

internal fun isTrustedExternalDeepLinkOrigin(scheme: String?, host: String?): Boolean {
    val normalizedScheme = scheme?.trim()?.lowercase().orEmpty()
    val normalizedHost = host?.trim()?.lowercase().orEmpty()
    if (normalizedScheme == "https" || normalizedScheme == "http") {
        return normalizedHost == "conectate.com" || normalizedHost == "www.conectate.com"
    }
    if (normalizedScheme != "conectate") return false
    return normalizedHost in setOf(
        "videos",
        "social",
        "messages",
        "create-moment",
        "post",
        "survey",
        "video",
        "profile",
        "group",
        "community",
        "web-login",
        "recovery",
    )
}

internal fun safeExternalRouteValue(value: String?, maxLength: Int = 160): String? {
    if (maxLength !in 1..2_048) return null
    val normalized = value?.trim().orEmpty()
    return normalized.takeIf {
        it.isNotEmpty() && it.length <= maxLength && it.none(Char::isISOControl)
    }
}

/** Storage/domain IDs are UUIDs or opaque alphanumeric identifiers, never paths. */
internal fun safeExternalRouteId(value: String?): String? = value?.trim()?.takeIf {
    it.length in 1..160 && it.matches(Regex("[A-Za-z0-9_-]+"))
}

/** Navigation can reject a stale/invalid destination; keep external intent recoverable. */
internal fun consumeExternalNavigation(
    clearIntent: () -> Unit,
    onRejected: () -> Unit,
    navigate: () -> Boolean,
): Boolean = try {
    navigate()
} catch (error: IllegalArgumentException) {
    clearIntent()
    onRejected()
    true
} catch (error: IllegalStateException) {
    clearIntent()
    onRejected()
    true
}
