package conectate.com.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper utility for dynamic permission verification across all Android versions
 * Safe to use on all Android brands (Xiaomi, Samsung, Oppo, etc.)
 */
object PermissionHelper {
    
    /**
     * Check if a specific permission is granted
     * @param context Application context
     * @param permission Permission to check (e.g., Manifest.permission.CAMERA)
     * @return true if permission is granted
     */
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == 
            PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if CAMERA permission is granted (for photo/video)
     */
    fun isCameraPermissionGranted(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.CAMERA)
    }
    
    /**
     * Check if RECORD_AUDIO permission is granted (for voice rooms)
     */
    fun isRecordAudioPermissionGranted(context: Context): Boolean {
        return isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)
    }
    
    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Not required on older versions
        }
    }
    
    /**
     * Check if media permissions are granted (Android 13+)
     * For reading images, videos, and audio files
     */
    fun areMediaPermissionsGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasFullVisualAccess =
                isPermissionGranted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                    isPermissionGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
            val hasSelectedVisualAccess =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    isPermissionGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

            (hasFullVisualAccess || hasSelectedVisualAccess) &&
                isPermissionGranted(context, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            // On older versions, check READ_EXTERNAL_STORAGE
            isPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    
    /**
     * Get list of permissions needed for a feature
     */
    fun getRequiredPermissionsForCamera(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA)
    }
    
    fun getRequiredPermissionsForVoiceRooms(): Array<String> {
        return arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    }
    
    fun getRequiredPermissionsForMediaUpload(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}


