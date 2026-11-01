package conectate.com;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** A standalone test APK receiver, with no dependency on the target app's Kotlin runtime. */
public final class ShareCaptureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String owner = getPackageName();
        if (owner.endsWith(".test")) owner = owner.substring(0, owner.length() - 5);
        CharSequence payload = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
        sendBroadcast(new Intent("novvin.sharing100.CAPTURED").setPackage(owner)
            .putExtra("shared_text", payload == null ? null : payload.toString()));
        finish();
    }
}
