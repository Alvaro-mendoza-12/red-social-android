package conectate.com.services

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps CPU and Wi-Fi radio responsive while a foreground call or voice room
 * session is active so locking the phone does not suspend active RTC media.
 */
internal class ActiveMediaWakeLocks(
    context: Context,
    private val tagPrefix: String,
) {
    private val appContext = context.applicationContext
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (cpuWakeLock == null && powerManager != null) {
                cpuWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Novvin:$tagPrefix:CpuWakeLock",
                ).apply { setReferenceCounted(false) }
            }
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(MAX_SESSION_WAKE_MS)
            }
        }
        runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wifiManager != null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(mode, "Novvin:$tagPrefix:WifiLock")
                    .apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        }
        cpuWakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private companion object {
        const val MAX_SESSION_WAKE_MS = 4L * 60L * 60L * 1_000L
    }
}
