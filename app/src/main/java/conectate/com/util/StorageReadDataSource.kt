package conectate.com.util

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession

/** Storage objects always consult the server; local explicit offline copies remain usable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StorageReadDataSource(
    private val upstream: DataSource.Factory,
    private val cached: DataSource.Factory,
    private val admitCachedRead: ((DataSpec) -> String?)? = null,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var guarded = false
    private var identity: StorageReadIdentity? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "La fuente multimedia ya está abierta." }
        guarded = StorageReadSession.isStorageRequest(dataSpec.uri.toString())
        identity = if (guarded) StorageReadSession.snapshot()?.identity else null
        if (guarded) StorageReadSession.requireCurrent(identity)
        val admittedKey = if (guarded) admitCachedRead?.invoke(dataSpec) else null
        if (guarded) StorageReadSession.requireCurrent(identity)
        val selected = (if (guarded && admittedKey == null) upstream else cached).createDataSource()
        listeners.forEach(selected::addTransferListener)
        delegate = selected
        return try {
            if (guarded) StorageReadSession.requireCurrent(identity)
            selected.open(if (admittedKey == null) dataSpec else dataSpec.buildUpon().setKey(admittedKey).build()).also { if (guarded) StorageReadSession.requireCurrent(identity) }
        } catch (error: Exception) { close(); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!guarded) return checkNotNull(delegate).read(buffer, offset, length)
        StorageReadSession.requireCurrent(identity)
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        val pending = ByteArray(minOf(length, 64 * 1024))
        val count = checkNotNull(delegate).read(pending, 0, pending.size)
        StorageReadSession.requireCurrent(identity)
        if (count > 0) pending.copyInto(buffer, offset, 0, count)
        return count
    }
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() {
        val closing = delegate
        delegate = null
        guarded = false
        identity = null
        closing?.close()
    }
}
