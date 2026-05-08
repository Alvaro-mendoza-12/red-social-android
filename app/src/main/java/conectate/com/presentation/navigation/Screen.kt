package conectate.com.presentation.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object PhoneVerification : Screen("phone_verification")
    object Home : Screen("home?startTab={startTab}&sharedText={sharedText}&sharedUri={sharedUri}") {
        fun createRoute(startTab: String? = null, sharedText: String? = null, sharedUri: String? = null): String {
            return buildString {
                append("home")
                val params = mutableListOf<String>()
                if (startTab != null) params.add("startTab=$startTab")
                if (sharedText != null) params.add("sharedText=${android.net.Uri.encode(sharedText)}")
                if (sharedUri != null) params.add("sharedUri=${android.net.Uri.encode(sharedUri)}")
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
        }
    }
    
    object Social : Screen("social")
    object Messages : Screen("messages")
    object Chat : Screen("chat/{userId}?initialMessage={initialMessage}&focusMessageId={focusMessageId}") {
        fun createRoute(
            userId: String,
            initialMessage: String? = null,
            focusMessageId: String? = null
        ): String {
            val base = "chat/$userId"
            val params = mutableListOf<String>()
            if (initialMessage != null) {
                params.add("initialMessage=${android.net.Uri.encode(initialMessage)}")
            }
            if (focusMessageId != null) {
                params.add("focusMessageId=${android.net.Uri.encode(focusMessageId)}")
            }
            return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
        }
    }
    
    object Videos : Screen("videos")
    object VideoPlayer : Screen("video_player/{videoId}?focusCommentId={focusCommentId}") {
        fun createRoute(videoId: String, focusCommentId: String? = null): String {
            val base = "video_player/$videoId"
            return if (focusCommentId.isNullOrBlank()) {
                base
            } else {
                "$base?focusCommentId=${android.net.Uri.encode(focusCommentId)}"
            }
        }
    }
    object VideoUpload : Screen("video_upload")
    
    object Profile : Screen("profile")
    object EditProfile : Screen("edit_profile")
    object Settings : Screen("settings")
    object AccountSettings : Screen("account_settings")
    object SecuritySettings : Screen("security_settings")
    object PrivacySettings : Screen("privacy_settings")
    object CustomizeApp : Screen("customize_app")
    object NotificationSettings : Screen("notification_settings")
    object ChatSettings : Screen("chat_settings")
    object StorageSettings : Screen("storage_settings")
    object LanguageSettings : Screen("language_settings")
    object ThemeSettings : Screen("theme_settings")
    object HelpSettings : Screen("help_settings")
    object AboutSettings : Screen("about_settings")
    object ChangePassword : Screen("change_password")
    object PrivacyPolicy : Screen("privacy_policy")
    object UserProfile : Screen("user_profile/{userId}") {
        fun createRoute(userId: String) = "user_profile/$userId"
    }
    
    object SearchUsers : Screen("search_users")
    object CreateGroup : Screen("create_group")
    object CreateCommunity : Screen("create_community")
    object CreateVoiceRoom : Screen("create_voice_room")

    object VoiceRoomSearch : Screen("voice_room_search")
    
    object GroupChat : Screen("group_chat/{groupId}?focusMessageId={focusMessageId}") {
        fun createRoute(groupId: String, focusMessageId: String? = null): String {
            val base = "group_chat/$groupId"
            return if (focusMessageId.isNullOrBlank()) {
                base
            } else {
                "$base?focusMessageId=${android.net.Uri.encode(focusMessageId)}"
            }
        }
    }
    object CommunityChat : Screen("community_chat/{communityId}?focusMessageId={focusMessageId}") {
        fun createRoute(communityId: String, focusMessageId: String? = null): String {
            val base = "community_chat/$communityId"
            return if (focusMessageId.isNullOrBlank()) {
                base
            } else {
                "$base?focusMessageId=${android.net.Uri.encode(focusMessageId)}"
            }
        }
    }
    
    object GroupDetail : Screen("group_detail/{groupId}") {
        fun createRoute(groupId: String) = "group_detail/$groupId"
    }
    object CommunityDetail : Screen("community_detail/{communityId}") {
        fun createRoute(communityId: String) = "community_detail/$communityId"
    }
    
    object VoiceRooms : Screen("voice_rooms")
    object RoomDetail : Screen("room_detail/{roomId}") {
        fun createRoute(roomId: String) = "room_detail/$roomId"
    }

    object FollowList : Screen("follow_list/{userId}/{type}") {
        fun createRoute(userId: String, type: String) = "follow_list/$userId/$type"
    }

    object AdminPanel : Screen("admin_panel")
    object MyReports : Screen("my_reports_v2")

    object FullScreenImage : Screen("fullscreen_image/{imageUrl}") {
        fun createRoute(imageUrl: String): String {
             val encoded = java.net.URLEncoder.encode(imageUrl, java.nio.charset.StandardCharsets.UTF_8.toString())
             return "fullscreen_image/$encoded"
        }
    }
    
    object FullScreenVideo : Screen("fullscreen_video/{videoUrl}") {
        fun createRoute(videoUrl: String): String {
             val encoded = java.net.URLEncoder.encode(videoUrl, java.nio.charset.StandardCharsets.UTF_8.toString())
             return "fullscreen_video/$encoded"
        }
    }

    object PostDetail : Screen("post_detail/{postId}?focusCommentId={focusCommentId}") {
        fun createRoute(postId: String, focusCommentId: String? = null): String {
            val base = "post_detail/$postId"
            return if (focusCommentId.isNullOrBlank()) {
                base
            } else {
                "$base?focusCommentId=${android.net.Uri.encode(focusCommentId)}"
            }
        }
    }

    object Notifications : Screen("notifications")
    object Ranking : Screen("ranking")
    object Call : Screen("call/{callId}") {
        fun createRoute(callId: String) = "call/$callId"
    }
    object CallHistory : Screen("call_history")
    object FriendRequests : Screen("friend_requests")
    
    object CreateMoment : Screen("create_moment")
    object MomentViewer : Screen("moment_viewer/{momentId}") {
        fun createRoute(momentId: String) = "moment_viewer/$momentId"
    }
    
    object LiveViewer : Screen("live_viewer/{streamId}") {
        fun createRoute(streamId: String) = "live_viewer/$streamId"
    }
    
    object LiveHost : Screen("live_host/{liveId}") {
        fun createRoute(liveId: String) = "live_host/$liveId"
    }
    object AddAccountLogin : Screen("add_account_login")
    object WebLoginScanner : Screen("web_login_scanner?pairId={pairId}&nonce={nonce}") {
        fun createRoute(
            pairId: String? = null,
            nonce: String? = null
        ): String {
            return buildString {
                append("web_login_scanner")
                val params = mutableListOf<String>()
                if (!pairId.isNullOrBlank()) {
                    params.add("pairId=${android.net.Uri.encode(pairId)}")
                }
                if (!nonce.isNullOrBlank()) {
                    params.add("nonce=${android.net.Uri.encode(nonce)}")
                }
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
        }
    }
    object NotificationAccountBridge : Screen("notification_account_bridge")
    
    object VideoEditor : Screen("video_editor/{videoUri}") {
        fun createRoute(videoUri: android.net.Uri): String {
            val encoded = java.net.URLEncoder.encode(videoUri.toString(), java.nio.charset.StandardCharsets.UTF_8.toString())
            return "video_editor/$encoded"
        }
    }
}
