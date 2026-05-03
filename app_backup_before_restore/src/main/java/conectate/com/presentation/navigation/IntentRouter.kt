package conectate.com.presentation.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import androidx.navigation.NavHostController
import conectate.com.data.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object IntentRouter {

    fun hasPendingNavigationIntent(pendingIntent: Intent?): Boolean {
        pendingIntent ?: return false
        val data = pendingIntent.data
        val isRecovery = (data?.scheme == "conectate" && data.host == "recovery") ||
            (data?.getQueryParameter("type") == "recovery") ||
            (data?.fragment?.contains("type=recovery") == true)
        val isDeepLink = data?.scheme in setOf("https", "http", "conectate")
        val isShare = pendingIntent.action == Intent.ACTION_SEND ||
            pendingIntent.action == Intent.ACTION_SEND_MULTIPLE
        val hasPushType = !pendingIntent.getStringExtra("type").isNullOrBlank()
        return isRecovery || isDeepLink || isShare || hasPushType
    }

    fun handleIntentNavigation(
        incomingIntent: Intent,
        navController: NavHostController,
        authRepository: AuthRepository,
        activityScope: CoroutineScope,
        clearIntent: () -> Unit
    ): Boolean {
        if (handleNotificationIntent(incomingIntent, navController, clearIntent)) return true
        if (handleUriIntent(incomingIntent, navController, authRepository, activityScope, clearIntent)) return true
        if (handleIncomingShareIntent(incomingIntent, navController, clearIntent)) return true
        return false
    }

    private fun handleNotificationIntent(
        incomingIntent: Intent,
        navController: NavHostController,
        clearIntent: () -> Unit
    ): Boolean {
        val type = incomingIntent.getStringExtra("type")?.trim()?.lowercase().orEmpty()
        if (type.isBlank()) return false

        when (type) {
            "new_message" -> {
                val chatId = incomingIntent.getStringExtra("chat_id")
                    ?: incomingIntent.getStringExtra("target_id")
                val senderId = incomingIntent.getStringExtra("sender_id")
                val messageId = incomingIntent.getStringExtra("message_id")
                    ?: incomingIntent.getStringExtra("focus_message_id")
                    ?: incomingIntent.getStringExtra("id")
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
                        navController.navigate(
                            Screen.CommunityChat.createRoute(
                                targetCommunityId,
                                focusMessageId = messageId
                            )
                        )
                        clearIntent()
                        return true
                    }
                    !targetGroupId.isNullOrBlank() -> {
                        navController.navigate(
                            Screen.GroupChat.createRoute(
                                targetGroupId,
                                focusMessageId = messageId
                            )
                        )
                        clearIntent()
                        return true
                    }
                    !chatId.isNullOrBlank() || !senderId.isNullOrBlank() -> {
                        val targetUserOrChat = senderId ?: chatId ?: return false
                        navController.navigate(
                            Screen.Chat.createRoute(
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
                    navController.navigate(
                        Screen.PostDetail.createRoute(
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
                        navController.navigate(
                            Screen.VideoPlayer.createRoute(
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
                        navController.navigate(
                            Screen.PostDetail.createRoute(
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
                    navController.navigate(
                        Screen.VideoPlayer.createRoute(
                            videoId,
                            focusCommentId = commentId
                        )
                    )
                    clearIntent()
                    return true
                }
            }

            "follow", "friend_request", "voice_access_request" -> {
                val followerId = incomingIntent.getStringExtra("follower_id")
                    ?: incomingIntent.getStringExtra("target_id")
                if (!followerId.isNullOrBlank()) {
                    navController.navigate(Screen.UserProfile.createRoute(followerId))
                    clearIntent()
                    return true
                }
            }

            "voice_invite", "voice_return" -> {
                val roomId = incomingIntent.getStringExtra("room_id")
                    ?: incomingIntent.getStringExtra("target_id")
                if (!roomId.isNullOrBlank()) {
                    navController.navigate(Screen.RoomDetail.createRoute(roomId))
                    clearIntent()
                    return true
                }
            }

            "challenge_invite" -> {
                navController.navigate(Screen.Home.createRoute(startTab = "social"))
                clearIntent()
                return true
            }
        }

        return false
    }

    private fun handleUriIntent(
        incomingIntent: Intent,
        navController: NavHostController,
        authRepository: AuthRepository,
        activityScope: CoroutineScope,
        clearIntent: () -> Unit
    ): Boolean {
        val uri = incomingIntent.data ?: return false
        android.util.Log.d("MainActivity", "Handling Deep Link: $uri")
        android.util.Log.d("MainActivity", "Scheme: ${uri.scheme}, Host: ${uri.host}, Fragment: ${uri.fragment}")

        if (uri.scheme == "https" && (uri.host == "conectate.com" || uri.host == "www.conectate.com")) {
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                when (pathSegments[0]) {
                    "post" -> {
                        val postId = pathSegments.getOrNull(1)
                        if (!postId.isNullOrBlank()) {
                            navController.navigate(Screen.PostDetail.createRoute(postId))
                            clearIntent()
                            return true
                        }
                    }
                    "video" -> {
                        val videoId = pathSegments.getOrNull(1)
                        if (!videoId.isNullOrBlank()) {
                            navController.navigate(Screen.VideoPlayer.createRoute(videoId))
                            clearIntent()
                            return true
                        }
                    }
                }
            }
        } else if (uri.scheme == "conectate") {
            when (uri.host) {
                "post" -> {
                    uri.pathSegments.firstOrNull()?.let { id ->
                        navController.navigate(Screen.PostDetail.createRoute(id))
                        clearIntent()
                        return true
                    }
                }
                "video" -> {
                    uri.pathSegments.firstOrNull()?.let { id ->
                        navController.navigate(Screen.VideoPlayer.createRoute(id))
                        clearIntent()
                        return true
                    }
                }
            }
        }

        val fragmentData = uri.fragment.orEmpty()
        val isRecovery = (uri.scheme == "conectate" && uri.host == "recovery") ||
            (uri.getQueryParameter("type") == "recovery") ||
            fragmentData.contains("type=recovery")
        if (!isRecovery) return false

        android.util.Log.d("MainActivity", "Recovery link detected, navigating to ChangePassword")
        clearIntent()

        val recoveryFragment = uri.fragment
        if (!recoveryFragment.isNullOrBlank()) {
            val params = recoveryFragment.split("&").mapNotNull { param ->
                val separator = param.indexOf('=')
                if (separator <= 0) null
                else param.substring(0, separator) to param.substring(separator + 1)
            }.toMap()
            val accessToken = params["access_token"]
            val refreshToken = params["refresh_token"]

            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                activityScope.launch {
                    try {
                        authRepository.importSession(accessToken, refreshToken)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Failed to import session", e)
                    } finally {
                        navController.navigate(Screen.ChangePassword.route)
                    }
                }
                return true
            }
        }

        navController.navigate(Screen.ChangePassword.route)
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

        val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedUri = if (incomingIntent.action == Intent.ACTION_SEND) {
            incomingIntent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)?.toString()
        } else {
            incomingIntent.parcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM)
                ?.firstOrNull()
                ?.toString()
        }

        if (sharedText == null && sharedUri == null) return false

        android.util.Log.d(
            "MainActivity",
            "Incoming share detected: action=${incomingIntent.action}, text=$sharedText, uri=$sharedUri"
        )
        navController.navigate(
            Screen.Home.createRoute(
                startTab = "social",
                sharedText = sharedText,
                sharedUri = sharedUri
            )
        )
        clearIntent()
        return true
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableArrayListExtraCompat(key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(key)
        }
    }
}
