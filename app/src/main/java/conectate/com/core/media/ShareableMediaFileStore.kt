package conectate.com.core.media

import android.content.Context
import java.io.File

/**
 * The only app-owned directories exposed through FileProvider.
 * Keep private caches, databases and offline message media outside this tree.
 */
object ShareableMediaFileStore {
    const val PROVIDER_ROOT = "file-provider"
    const val CROPS = "crops"
    const val CHALLENGES = "challenges"
    const val MOMENT_CAPTURES = "moment-captures"

    fun crops(context: Context): File = cacheDirectory(context, CROPS)

    fun challenges(context: Context): File = cacheDirectory(context, CHALLENGES)

    fun momentCaptures(context: Context, preferExternal: Boolean): File {
        val root = if (preferExternal) context.externalCacheDir ?: context.cacheDir else context.cacheDir
        return File(File(root, PROVIDER_ROOT), MOMENT_CAPTURES).apply { mkdirs() }
    }

    private fun cacheDirectory(context: Context, child: String): File =
        File(File(context.cacheDir, PROVIDER_ROOT), child).apply { mkdirs() }
}
