package conectate.com.services

import android.content.Context
import android.content.Intent
import conectate.com.MainActivity
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.TrustedNavigationIntents
import conectate.com.util.putNotificationAccountTarget

internal fun voiceRoomReturnIntent(context: Context, roomId: String, account: NotificationAccountTarget?): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("room_id", roomId)
        putExtra("type", "voice_return")
        putNotificationAccountTarget(account)
    }.also { TrustedNavigationIntents.markTrusted(context, it) }
