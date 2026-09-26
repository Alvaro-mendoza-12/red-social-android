package conectate.com.core.media

import java.io.File

/** The account key is opaque and lets a confirmation target the account it displayed. */
data class ChatOfflineMediaUsage(
    val accountKey: String,
    val retainedBytes: Long,
    val prefetchedBytes: Long,
    val fileCount: Int
) {
    val totalBytes: Long get() = retainedBytes + prefetchedBytes
}

/** Called under the owning cache's file lock, never while waiting on a network response. */
internal object ChatMediaStoragePolicy {
    private fun marker(file: File) = File(file.parentFile, "${file.name}.keep")

    fun isRetained(file: File): Boolean = marker(file).isFile

    fun retain(file: File): Boolean = file.isFile && file.length() > 0L &&
        (isRetained(file) || runCatching { marker(file).createNewFile() }.getOrDefault(false))

    fun usage(directory: File, accountKey: String): ChatOfflineMediaUsage {
        val files = mediaFiles(directory)
        return ChatOfflineMediaUsage(
            accountKey = accountKey,
            retainedBytes = files.filter(::isRetained).sumOf(File::length),
            prefetchedBytes = files.filterNot(::isRetained).sumOf(File::length),
            fileCount = files.size
        )
    }

    /** Only speculative prefetch is evictable; opened files never spend that budget. */
    fun prunePrefetch(directory: File, incomingBytes: Long, quotaBytes: Long, protectedFile: File): Boolean {
        val candidates = mediaFiles(directory).filterNot(::isRetained)
        var usedBytes = candidates.sumOf(File::length)
        for (candidate in candidates.filter { it != protectedFile }.sortedBy(File::lastModified)) {
            if (usedBytes + incomingBytes <= quotaBytes) break
            val length = candidate.length()
            if (candidate.delete()) usedBytes -= length
        }
        return usedBytes + incomingBytes <= quotaBytes
    }

    private fun mediaFiles(directory: File): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".media") && it.length() > 0L }
        .orEmpty()
}
