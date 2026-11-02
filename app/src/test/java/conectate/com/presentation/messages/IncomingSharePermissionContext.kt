package conectate.com.presentation.messages

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Simulates explicit OS URI grants separately from this app's broad storage/contact permissions. */
internal class IncomingSharePermissionContext(base: Context, private val readable: Set<Uri>) : ContextWrapper(base) {
    var lastPid: Int? = null
    var lastUid: Int? = null
    override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
        lastPid = pid; lastUid = uid
        return if (uri in readable && modeFlags == Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
}
