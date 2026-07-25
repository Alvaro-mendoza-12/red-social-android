package conectate.com.presentation.live

import android.Manifest
import conectate.com.data.model.LiveSource

data class LiveHostRuntimeRequirements(
    val liveId: String,
    val source: LiveSource,
    val micEnabled: Boolean,
    val videoEnabled: Boolean
) {
    val permissions: Set<String>
        get() = requiredHostRuntimePermissions(
            source = source,
            micEnabled = micEnabled,
            videoEnabled = videoEnabled
        )
}

internal fun requiredHostRuntimePermissions(
    source: LiveSource,
    micEnabled: Boolean,
    videoEnabled: Boolean
): Set<String> = buildSet {
    if (videoEnabled && source == LiveSource.CAMERA) {
        add(Manifest.permission.CAMERA)
    }
    if (micEnabled) {
        add(Manifest.permission.RECORD_AUDIO)
    }
}

internal fun liveSourceFromWire(value: String): LiveSource =
    if (value.trim().equals("screen", ignoreCase = true)) {
        LiveSource.SCREEN
    } else {
        LiveSource.CAMERA
    }
