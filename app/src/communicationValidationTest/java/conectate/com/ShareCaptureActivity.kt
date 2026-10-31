package conectate.com

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Runs in the separate test APK so Android's actual sharesheet has an external receiver. */
class ShareCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent("novvin.sharing100.CAPTURED").setPackage(packageName.removeSuffix(".test"))
            .putExtra("shared_text", intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()))
        finish()
    }
}
