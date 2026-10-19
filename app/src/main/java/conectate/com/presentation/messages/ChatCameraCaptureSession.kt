package conectate.com.presentation.messages

/** A callback from a closed or backgrounded capture must never open an editor later. */
internal class ChatCameraCaptureSession(private var foreground: Boolean = true) {
    var isOpen: Boolean = true
        private set
    private var generation = 0L

    fun begin(): Long? = if (isOpen && foreground) ++generation else null
    fun accepts(token: Long): Boolean = isOpen && foreground && token == generation
    fun start() { foreground = true }
    fun stop() { foreground = false; generation++ }
    fun close() { isOpen = false; stop() }
}
