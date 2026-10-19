package conectate.com.presentation.messages

import androidx.camera.core.ImageCapture

internal fun nextChatCameraFlash(current: Int, video: Boolean): Int = when (current) {
    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
    ImageCapture.FLASH_MODE_ON -> if (video) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_AUTO
    else -> ImageCapture.FLASH_MODE_OFF
}

internal fun chatCameraUsesTorch(flash: Int, hasFlash: Boolean): Boolean =
    hasFlash && flash == ImageCapture.FLASH_MODE_ON

internal fun chatCameraFlashLabel(flash: Int, hasFlash: Boolean, torchOn: Boolean, pending: Boolean): String = when {
    !hasFlash -> "Sin flash"
    pending -> "Preparando flash…"
    flash == ImageCapture.FLASH_MODE_AUTO -> "Flash automático"
    flash == ImageCapture.FLASH_MODE_ON && torchOn -> "Flash encendido"
    flash == ImageCapture.FLASH_MODE_ON -> "Activando flash…"
    else -> "Flash apagado"
}
