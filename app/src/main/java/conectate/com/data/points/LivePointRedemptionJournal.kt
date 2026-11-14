package conectate.com.data.points

import android.content.Context
import android.util.AtomicFile
import conectate.com.data.model.LivePointRedemptionResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface LivePointRedemptionStore {
    suspend fun execute(
        owner: String,
        liveId: String,
        rewardId: String,
        requireCurrent: () -> Unit,
        send: suspend (String) -> LivePointRedemptionResponse,
    ): LivePointRedemptionResponse
}

/** Saves the retry identity before dispatch. Contains no credentials and is excluded from device backup. */
@Singleton
internal class LivePointRedemptionJournal internal constructor(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LivePointRedemptionStore {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "live-point-redemptions-v1"))

    @Serializable
    private data class Pending(val owner: String, val liveId: String, val rewardId: String, val nonce: String)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(
        owner: String,
        liveId: String,
        rewardId: String,
        requireCurrent: () -> Unit,
        send: suspend (String) -> LivePointRedemptionResponse,
    ): LivePointRedemptionResponse = withContext(ioDispatcher) {
        val expected = Pending(clean(owner), clean(liveId), clean(rewardId), "")
        val file = file(expected)
        val pending = mutex(file).withLock {
            currentCoroutineContext().ensureActive(); requireCurrent()
            read(file, expected) ?: expected.copy(nonce = UUID.randomUUID().toString()).also { write(file, it) }
        }
        currentCoroutineContext().ensureActive(); requireCurrent()
        // Do not hold the storage lock over HTTP. Overlapping callers recover the
        // same nonce; the server serializes it across processes and checks ownership.
        val response = send(pending.nonce)
        currentCoroutineContext().ensureActive(); requireCurrent()
        mutex(file).withLock {
            currentCoroutineContext().ensureActive(); requireCurrent()
            // Another acknowledged request may already have cleared this nonce.
            // An older acknowledgement must never remove a newer pending command.
            if (read(file, expected)?.nonce == pending.nonce) {
                file.delete()
                check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                    "No pudimos confirmar el canje. Reintenta para recuperar su estado."
                }
            }
        }
        response
    }

    private fun clean(value: String): String = value.trim().lowercase(Locale.ROOT).also {
        require(it.isNotBlank() && it.length <= 160 && '\u0000' !in it) { "El canje no tiene un destino válido." }
    }

    private fun file(pending: Pending): AtomicFile {
        val key = listOf(pending.owner, pending.liveId, pending.rewardId).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.json"))
    }

    private fun read(file: AtomicFile, expected: Pending): Pending? {
        val bytes = try { file.readFully() } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
            return null
        }
        check(bytes.size <= 4096) { "No pudimos recuperar el canje pendiente." }
        return json.decodeFromString<Pending>(bytes.toString(Charsets.UTF_8)).also {
            check(it.owner == expected.owner && it.liveId == expected.liveId && it.rewardId == expected.rewardId &&
                runCatching { UUID.fromString(it.nonce).toString() == it.nonce }.getOrDefault(false)) {
                "No pudimos recuperar el canje pendiente."
            }
        }
    }

    private fun write(file: AtomicFile, pending: Pending) {
        check(directory.isDirectory || directory.mkdirs()) { "No pudimos guardar el canje. Reintenta." }
        val bytes = json.encodeToString(pending).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            check(file.readFully().contentEquals(bytes)) { "No pudimos guardar el canje. Reintenta." }
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
        fun mutex(file: AtomicFile): Mutex = locks.getOrPut(file.baseFile.canonicalPath) { Mutex() }
    }
}
