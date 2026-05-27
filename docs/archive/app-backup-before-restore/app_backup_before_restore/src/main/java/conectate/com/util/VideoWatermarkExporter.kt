package conectate.com.util

import android.content.Context
import java.io.File

object VideoWatermarkExporter {
    fun exportWithBrandWatermark(
        context: Context,
        inputFile: File,
        userName: String
    ): Result<File> {
        return Result.success(inputFile)
    }
}
