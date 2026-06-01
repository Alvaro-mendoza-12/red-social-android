package conectate.com.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VoiceForegroundService : Service() {
    companion object {
        const val ACTION_START = "conectate.com.action.START_VOICE_FOREGROUND"
        const val ACTION_UPDATE = "conectate.com.action.UPDATE_VOICE_FOREGROUND"
        const val ACTION_STOP = "conectate.com.action.STOP_VOICE_FOREGROUND"

        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_READABLE_ID = "extra_readable_id"
        const val EXTRA_PARTICIPANT_COUNT = "extra_participant_count"
        const val EXTRA_IS_MUTED = "extra_is_muted"
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
