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
    object Games : Screen("games")
    object GamingProfile : Screen("games_profile")
    object AddGame : Screen("games_add")
    object PlayerDetail : Screen("games_player/{playerId}") {
        fun createRoute(playerId: String) = "games_player/${android.net.Uri.encode(playerId)}"
    }
    object MatchFound : Screen("games_match/{sessionId}") {
        fun createRoute(sessionId: String) = "games_match/${android.net.Uri.encode(sessionId)}"
    }
    object Messages : Screen("messages")
    object Novvin : Screen("novvin")
    object Chat : Screen("chat/{userId}?initialMessage={initialMessage}&focusMessageId={focusMessageId}") {
        fun createRoute(
            userId: String,
            initialMessage: String? = null,
            focusMessageId: String? = null
        ): String {
            val initial = initialMessage?.let { android.net.Uri.encode(it) } ?: ""
            val focus = focusMessageId?.let { android.net.Uri.encode(it) } ?: ""
            return "chat/$userId?initialMessage=$initial&focusMessageId=$focus"
        }
    }
    
    object Videos : Screen("videos")
    object VideoDiscovery : Screen("video_discovery/{videoId}") {
        fun createRoute(videoId: String) = "video_discovery/${android.net.Uri.encode(videoId)}"
    }
    /** Feed vertical limitado a los videos publicados por un perfil. */
    object ProfileVideoFeed : Screen("profile_video_feed/{profileId}/{videoId}?initialIndex={initialIndex}") {
        fun createRoute(profileId: String, videoId: String, initialIndex: Int = 0): String =
            "profile_video_feed/${android.net.Uri.encode(profileId)}/${android.net.Uri.encode(videoId)}" +
                "?initialIndex=${initialIndex.coerceAtLeast(0)}"
    }
    object VideoPlayer : Screen("video_player/{videoId}?focusCommentId={focusCommentId}&resumeMs={resumeMs}&endMs={endMs}") {
        fun createRoute(videoId: String, focusCommentId: String? = null, resumeMs: Long = 0L,
                        endMs: Long = 0L): String {
            val base = "video_player/$videoId"
            val params = mutableListOf<String>()
            if (!focusCommentId.isNullOrBlank()) params += "focusCommentId=${android.net.Uri.encode(focusCommentId)}"
            if (resumeMs > 0L) params += "resumeMs=$resumeMs"
            if (endMs > 0L) params += "endMs=$endMs"
            return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
        }
    }
    object VideoUpload : Screen("video_upload")
    object OfflineVideos : Screen("offline_videos")
    object CreatePost : Screen("create_post")
    
    object Profile : Screen("profile")
    object EditProfile : Screen("edit_profile")
    object Settings : Screen("settings")
    object CoinsAndPayments : Screen("coins_and_payments")
    object CreatorEarnings : Screen("creator_earnings")
    object CreatorAnalytics : Screen("creator_analytics")
    object SettingsCategory : Screen("settings_category/{category}") {
        fun createRoute(category: String) = "settings_category/${android.net.Uri.encode(category)}"
    }
    object AccountSettings : Screen("account_settings")
    object SecuritySettings : Screen("security_settings")
    object PrivacySettings : Screen("privacy_settings")
    object ContentSafety : Screen("content_safety")
    object CustomizeApp : Screen("customize_app")
    object NotificationSettings : Screen("notification_settings")
    object ChatSettings : Screen("chat_settings")
    object StorageSettings : Screen("storage_settings")
    object LanguageSettings : Screen("language_settings")
    object ThemeSettings : Screen("theme_settings")
    object HelpSettings : Screen("help_settings")
    object TermsAndConditions : Screen("terms_and_conditions")
    object AboutSettings : Screen("about_settings")
    object ChangePassword : Screen("change_password")
    object PrivacyPolicy : Screen("privacy_policy")
    object UserProfile : Screen("user_profile/{userId}") {
        fun createRoute(userId: String) = "user_profile/$userId"
    }
    
    object SearchUsers : Screen("search_users")
    object Proximity : Screen("proximity")
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
    
    object GroupDetail : Screen("group_detail/{groupId}?invite={invite}") {
        fun createRoute(groupId: String, invite: String? = null): String {
            val base = "group_detail/$groupId"
            return if (invite.isNullOrBlank()) base else "$base?invite=${android.net.Uri.encode(invite)}"
        }
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
    object MomentHighlightViewer : Screen("moment_highlight/{highlightId}?title={title}") {
        fun createRoute(highlightId: String, title: String): String =
            "moment_highlight/${android.net.Uri.encode(highlightId)}?title=${android.net.Uri.encode(title)}"
    }
    
    object LiveViewer : Screen("live_viewer/{streamId}") {
        fun createRoute(streamId: String) = "live_viewer/$streamId"
    }

    object LiveReplay : Screen("live_replay/{liveId}") {
        fun createRoute(liveId: String) = "live_replay/$liveId"
    }

    object LiveSetup : Screen("live_setup")
    object LiveDiscovery : Screen("live_discovery")
    
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
    object NotificationAccountBridge : Screen(
        "notification_account_bridge?destination={destination}&targetAccountId={targetAccountId}" +
            "&targetAccountLabel={targetAccountLabel}&currentAccountLabel={currentAccountLabel}" +
            "&contextTitle={contextTitle}&preview={preview}"
    ) {
        fun createRoute(
            destinationRoute: String,
            targetAccountId: String? = null,
            targetAccountLabel: String? = null,
            currentAccountLabel: String? = null,
            contextTitle: String? = null,
            preview: String? = null
        ): String {
            val destination = destinationRoute.trim().takeIf { it.isNotBlank() }
                ?: Home.createRoute(startTab = Messages.route)
            val params = listOfNotNull(
                "destination=${android.net.Uri.encode(destination.take(2_000))}",
                targetAccountId?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "targetAccountId=${android.net.Uri.encode(it.take(200))}" },
                targetAccountLabel?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "targetAccountLabel=${android.net.Uri.encode(it.take(120))}" },
                currentAccountLabel?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "currentAccountLabel=${android.net.Uri.encode(it.take(120))}" },
                contextTitle?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "contextTitle=${android.net.Uri.encode(it.take(180))}" },
                preview?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { "preview=${android.net.Uri.encode(it.take(500))}" }
            )
            return "notification_account_bridge?${params.joinToString("&")}"
        }
    }
    
    object VideoEditor : Screen("video_editor/{videoUri}?soundUri={soundUri}") {
        fun createRoute(videoUri: android.net.Uri, soundUri: android.net.Uri? = null): String {
            val encoded = java.net.URLEncoder.encode(videoUri.toString(), java.nio.charset.StandardCharsets.UTF_8.toString())
            val encodedSound = soundUri?.let {
                java.net.URLEncoder.encode(it.toString(), java.nio.charset.StandardCharsets.UTF_8.toString())
            }
            return "video_editor/$encoded${encodedSound?.let { "?soundUri=$it" }.orEmpty()}"
        }
    }
}
