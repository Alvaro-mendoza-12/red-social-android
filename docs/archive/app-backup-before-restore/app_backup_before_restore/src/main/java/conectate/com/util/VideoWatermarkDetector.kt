package conectate.com.util

import android.content.Context
import android.net.Uri

data class VideoWatermarkDetection(
    val brand: String
)

object VideoWatermarkDetector {
    fun detect(context: Context, videoUri: Uri): VideoWatermarkDetection? {
        return null
    }
}
