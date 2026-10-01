package conectate.com.data.livekit

import android.content.Context
import livekit.org.webrtc.Camera1Enumerator
import livekit.org.webrtc.Camera2Enumerator
import livekit.org.webrtc.CameraEnumerator
import livekit.org.webrtc.NovvinCamera2Capturer
import livekit.org.webrtc.VideoCapturer

/** The guest's SFU camera uses the same steady AE policy as the native broadcast camera. */
internal fun createSteadyCollaborationCamera(context: Context): VideoCapturer {
    val enumerators = buildList<CameraEnumerator> {
        if (Camera2Enumerator.isSupported(context)) add(Camera2Enumerator(context))
        add(Camera1Enumerator(true))
    }
    enumerators.forEach { enumerator ->
        val cameras = (enumerator.deviceNames.filter(enumerator::isFrontFacing) + enumerator.deviceNames).distinct()
        cameras.forEach { camera ->
            val capturer = runCatching {
                if (enumerator is Camera2Enumerator) NovvinCamera2Capturer(context, camera, null)
                else enumerator.createCapturer(camera, null)
            }.getOrNull()
            if (capturer != null) return capturer
        }
    }
    error("No se encontró una cámara disponible para el directo.")
}
