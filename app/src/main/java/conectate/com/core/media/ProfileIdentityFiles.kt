package conectate.com.core.media

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class ProfileIdentityUsage(val accountKey: String, val bytes: Long, val imageCount: Int)

/** Private, account-bound identity thumbnails. Only anonymous/speculative files use LRU. */
internal class ProfileIdentityFiles(
    private val root: File,
    private val availableBytes: (File) -> Long = File::getUsableSpace,
    private val publish: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    private val minFreeBytes: Long = 64L * 1024L * 1024L,
) {
    fun imageFile(accountId: String?, sourceKey: String): File? {
        if (sourceKey.isBlank()) return null
        val directory = if (accountId == null) root else accountDirectory(accountId) ?: return null
        return File(directory, "${digest(sourceKey)}.webp")
    }

    fun usage(accountId: String): ProfileIdentityUsage? {
        val key = accountKey(accountId) ?: return null
        val directory = accountDirectory(accountId) ?: return null
        val images = directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(IMAGE_NAME) && it.length() > 0L }
        return ProfileIdentityUsage(key, images.sumOf(File::length), images.size)
    }

    fun canMigrateLegacy(accountId: String): Boolean = accountDirectory(accountId)
        ?.let { !File(it, MIGRATION_DISABLED).exists() } == true

    /** Deletes only this confirmed account's identity files, not generic caches or other accounts. */
    fun clear(accountId: String, expectedAccountKey: String): Boolean {
        if (accountKey(accountId) != expectedAccountKey) return false
        val directory = accountDirectory(accountId) ?: return false
        return try {
            if (!directory.canonicalPath.startsWith(root.canonicalPath + File.separator)) return false
            if (!directory.isDirectory && !directory.mkdirs()) return false
            // Prevent an old shared fallback from immediately undoing an explicit clear.
            val marker = File(directory, MIGRATION_DISABLED)
            if (!marker.isFile && !marker.createNewFile()) return false
            directory.listFiles().orEmpty()
                .filter { it.isFile && (it.name.matches(IMAGE_NAME) || (it.name.startsWith("identity-") && it.extension == "tmp")) }
                .map(File::delete).all { it }
        } catch (_: Exception) { false }
    }

    private fun accountDirectory(accountId: String): File? = accountKey(accountId)?.let { File(root, "accounts/$it") }

    fun find(accountId: String?, sourceKey: String): File? = imageFile(accountId, sourceKey)
        ?.takeIf { it.isFile && it.length() > 0L }

    /** A failed replacement never deletes the last complete thumbnail. Caller serializes writes. */
    fun write(accountId: String?, sourceKey: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > MAX_ENTRY_BYTES) return null
        val target = imageFile(accountId, sourceKey) ?: return null
        val directory = target.parentFile ?: return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (availableBytes(directory) < minFreeBytes + bytes.size) return null
        var temporary: File? = null
        return try {
            temporary = File.createTempFile("identity-", ".tmp", directory)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(publish(temporary, target)) { "Identity thumbnail publication failed" }
            target
        } catch (_: Exception) {
            null
        } finally {
            temporary?.delete()
        }
    }

    companion object {
        const val MAX_ENTRY_BYTES = 1024 * 1024
        private const val MIGRATION_DISABLED = ".legacy-migration-disabled"
        private val IMAGE_NAME = Regex("[0-9a-f]{64}\\.webp")
        fun accountKey(accountId: String): String? = accountId.trim().lowercase(Locale.ROOT)
            .takeIf(String::isNotBlank)?.let { digest("account:$it") }
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
