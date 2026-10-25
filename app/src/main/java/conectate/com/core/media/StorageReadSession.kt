package conectate.com.core.media

import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal data class StorageReadIdentity(val accountId: String, val generation: Long)

/** Credentials are kept in memory, never in cache names, URLs, logs or query strings. */
internal class StorageReadCredential(val identity: StorageReadIdentity, val accessToken: String) {
    override fun toString(): String = "StorageReadCredential([redacted])"
}

internal object StorageReadSession {
    @Volatile private var provider: () -> StorageReadCredential? = { null }
    @Volatile private var guestProvider: () -> StorageReadCredential? = { null }
    @Volatile private var guestActive: () -> Boolean = { false }
    @Volatile private var prepareGuestProvider: (StorageReadIdentity) -> StorageReadCredential? = { guestSnapshot() }
    @Volatile private var prepareProvider: (StorageReadIdentity) -> StorageReadCredential? = { snapshot() }
    @Volatile private var cachedScopeDigest: Pair<String, String>? = null

    fun install(credentials: () -> StorageReadCredential?) { install(credentials) { snapshot() } }

    fun install(
        credentials: () -> StorageReadCredential?,
        prepare: (StorageReadIdentity) -> StorageReadCredential?,
    ) {
        provider = credentials
        prepareProvider = prepare
    }

    /** A visible guest preview cannot borrow credentials from a saved member session. */
    fun installGuest(
        credentials: () -> StorageReadCredential?,
        prepare: (StorageReadIdentity) -> StorageReadCredential? = { guestSnapshot() },
        active: () -> Boolean = { false },
    ) {
        guestProvider = credentials
        prepareGuestProvider = prepare
        guestActive = active
    }

    private fun guestSnapshot(): StorageReadCredential? =
        validated(guestProvider)?.takeIf { it.identity.accountId.startsWith("guest_") }

    /** Called on an OkHttp worker before a storage read, never on the UI thread. */
    fun readySnapshot(): StorageReadCredential? {
        val expected = snapshot()?.identity ?: return null
        val prepared = runCatching {
            if (expected.accountId.startsWith("guest_")) prepareGuestProvider(expected)
            else prepareProvider(expected)
        }.getOrNull()
        requireCurrent(expected)
        if (prepared == null || prepared.identity != expected) throw IOException("No se pudo renovar la sesión para abrir la multimedia.")
        return prepared
    }

    fun snapshot(): StorageReadCredential? =
        if (runCatching { guestActive() }.getOrDefault(false)) guestSnapshot() else validated(provider)

    private fun validated(source: () -> StorageReadCredential?): StorageReadCredential? =
        runCatching { source() }.getOrNull()?.takeIf {
            it.identity.accountId.isNotBlank() && it.identity.accountId.length <= 256 &&
                it.identity.accountId.none(Char::isISOControl) &&
                it.accessToken.length in 1..8192 && it.accessToken.all { c -> c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-" }
        }

    fun isStorageRequest(url: String?): Boolean = StorageReadPolicy.accepts(url, UbuntuStorageUrl.baseUrl)

    fun requireCurrent(expected: StorageReadIdentity?) {
        if (snapshot()?.identity != expected) throw IOException("La cuenta activa cambió durante la lectura.")
    }

    /** Private URLs may reside in a nominally public bucket, including legacy attachments. */
    fun scopedCacheKey(url: String, originalKey: String): String {
        if (!isStorageRequest(url)) return originalKey
        val identity = snapshot()?.identity
        val scope = if (identity == null) "anonymous" else "${identity.accountId}:${identity.generation}"
        val digest = cachedScopeDigest?.takeIf { it.first == scope }?.second ?: run {
            val hex = "0123456789abcdef"
            val bytes = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            buildString(64) { bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4]); append(hex[value and 15])
            } }.also { cachedScopeDigest = scope to it }
        }
        return "storage-read-v1:$digest:$originalKey"
    }

    fun guardInputStream(url: String, input: InputStream, expected: StorageReadIdentity?): InputStream {
        if (!isStorageRequest(url)) return input
        return object : FilterInputStream(input) {
            private var pending = ByteArray(0)
            override fun read(): Int {
                requireCurrent(expected)
                return `in`.read().also { requireCurrent(expected) }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                requireCurrent(expected)
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (length == 0) return 0
                // A socket read may block while logout completes. Do not copy its
                // newly received bytes into the caller's buffer until identity is checked again.
                val readLength = minOf(length, 64 * 1024)
                if (pending.size < readLength) pending = ByteArray(readLength)
                val scratch = pending
                val count = `in`.read(scratch, 0, readLength)
                requireCurrent(expected)
                if (count > 0) scratch.copyInto(buffer, offset, 0, count)
                return count
            }
            override fun skip(count: Long): Long {
                requireCurrent(expected)
                return `in`.skip(count).also { requireCurrent(expected) }
            }
            override fun close() {
                pending = ByteArray(0)
                super.close()
            }
        }
    }
}
