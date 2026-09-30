package conectate.com.data.webrtc

/** Pending objects remain owned until delayed disposal or engine teardown claims them. */
internal class DeferredNativeDisposals<T : Any> {
    private val entries = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
    @Synchronized fun add(value: T) { entries.add(value) }
    @Synchronized fun claim(value: T): Boolean = entries.remove(value)
    @Synchronized fun drain(): List<T> = entries.toList().also { entries.clear() }
}
