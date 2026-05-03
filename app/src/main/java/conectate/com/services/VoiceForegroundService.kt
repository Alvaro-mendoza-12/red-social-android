package conectate.com.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VoiceForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
