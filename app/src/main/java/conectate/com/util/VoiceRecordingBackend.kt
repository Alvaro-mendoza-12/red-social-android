package conectate.com.util

import java.io.File

/** Native resources belong to one attempt, including its asynchronous finalization. */
interface VoiceRecordingBackend {
    fun start(append: Boolean = false, onFailure: (Exception) -> Unit = {}): File?
    fun pause(): Boolean
    fun resume(): Boolean
    fun detachFinalization(): VoiceRecordingFinalization?
    fun cancel()
}

interface VoiceRecordingFinalization {
    suspend fun finish(): File?
    /** Safe before, during or after finish; never touches a subsequent recording. */
    fun discard()
}
