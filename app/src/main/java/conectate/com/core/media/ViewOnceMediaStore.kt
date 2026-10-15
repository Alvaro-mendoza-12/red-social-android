package conectate.com.core.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private ephemeral files. No gallery, FileProvider grants or durable playback cache. */
internal object ViewOnceMediaStore {
    private data class Entry(val file: File, val identity: StorageReadIdentity, val expires: Long, val job: Job?)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Any()
    private var initialized = false
    @Volatile private var foreground = true
    private var epoch = 0L

    private fun initialize(context: Context): File = synchronized(lock) {
        val directory = File(context.applicationContext.cacheDir, "view-once-private")
        check(directory.isDirectory || directory.mkdirs()) { "No hay espacio para abrir el archivo." }
        if (!initialized) {
            directory.listFiles()?.filter { it.isFile && it.name.startsWith("once-") }?.forEach { it.delete() }
            initialized = true
            Handler(Looper.getMainLooper()).post {
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { foreground = true }
                    override fun onStop(owner: LifecycleOwner) { foreground = false; clear() }
                })
            }
        }
        directory
    }

    suspend fun download(context: Context, lease: ViewOnceLease, calls: okhttp3.Call.Factory = StorageReadDownloads.networkCalls()): String {
        require(lease.bytes in 1..ViewOnceLeasePolicy.MAX_BYTES && lease.kind in setOf("view_once_image", "view_once_video")) { "El archivo no es válido." }
        require(lease.expiresAtMillis > System.currentTimeMillis()) { "La autorización del archivo caducó." }
        val identity = StorageReadSession.snapshot()?.identity ?: error("Inicia sesión para abrir el archivo.")
        val directory = initialize(context)
        check(foreground && directory.usableSpace >= lease.bytes + 32L * 1024 * 1024) { "No hay espacio para abrir el archivo." }
        val startEpoch = synchronized(lock) { epoch }
        val extension = if (lease.kind == "view_once_image") ".image" else ".video"
        val file = File.createTempFile("once-", extension, directory)
        val url = file.toURI().toString()
        val entry = Entry(file, identity, lease.expiresAtMillis, currentCoroutineContext()[Job])
        synchronized(lock) {
            if (!foreground || epoch != startEpoch) { file.delete(); throw CancellationException("La aplicación pasó a segundo plano.") }
            entries[url] = entry
        }
        try {
            StorageReadSession.requireCurrent(identity)
            val count = StorageReadDownloads.downloadToFile(
                lease.url, file, lease.bytes, allowedMimeTypes = { mime ->
                    if (lease.kind == "view_once_image") Regex("image/(png|jpeg|webp|gif|avif|bmp)").matches(mime)
                    else mime in setOf("video/mp4", "video/webm", "video/quicktime", "video/x-m4v")
                }, requireMimeType = true, calls = calls,
            )
            currentCoroutineContext().ensureActive()
            StorageReadSession.requireCurrent(identity)
            check(count == lease.bytes && owns(url)) { "El archivo no se pudo abrir por completo." }
            return url
        } catch (error: Throwable) { release(url); throw error }
    }

    fun owns(url: String): Boolean {
        val entry = entries[url] ?: return false
        return foreground && entry.file.isFile && entry.expires > System.currentTimeMillis() &&
            StorageReadSession.snapshot()?.identity == entry.identity
    }

    fun release(url: String) { entries.remove(url)?.file?.delete() }

    private fun clear() = synchronized(lock) {
        epoch++
        val stale = entries.values.toList(); entries.clear()
        stale.forEach { it.job?.cancel(CancellationException("La aplicación pasó a segundo plano.")); it.file.delete() }
    }
}
