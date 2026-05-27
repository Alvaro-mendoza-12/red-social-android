package conectate.com.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d("BootReceiver", "Device rebooted. Ensuring push notifications are active.")
            // FCM is usually handled by Google Play Services automatically on boot,
            // but receiving this broadcast ensures the app process is recognized as active
            // and can help "wake up" any pending sync work if necessary.
        }
    }
}
