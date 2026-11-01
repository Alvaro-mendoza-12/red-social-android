package conectate.com.core.media

import android.graphics.Matrix

/** EXIF orientation is a transform of the pixels, including reflected camera images. */
internal fun exifOrientationMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        2 -> setScale(-1f, 1f)
        3 -> setRotate(180f)
        4 -> setScale(1f, -1f)
        5 -> { setRotate(90f); postScale(-1f, 1f) }
        6 -> setRotate(90f)
        7 -> { setRotate(-90f); postScale(-1f, 1f) }
        8 -> setRotate(-90f)
    }
}
