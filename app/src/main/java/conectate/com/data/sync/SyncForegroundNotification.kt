package conectate.com.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import conectate.com.R

/** WorkManager uses this notification for expedited work on Android before 12. */
internal fun syncForegroundInfo(context: Context): ForegroundInfo {
    val channelId = "novvin_pending_sync"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Envíos pendientes", NotificationManager.IMPORTANCE_LOW),
        )
    }
    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Novvin")
        .setContentText("Sincronizando mensajes pendientes")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .build()
    return ForegroundInfo(9_104, notification)
}
