package conectate.com.presentation.messages;

import android.net.Uri;
import java.io.File;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Keep host drive letters and separators parseable when a file URI is saved and reopened. */
@Implements(Uri.class)
public class PortableFileUriShadow {
    @Implementation
    protected static Uri fromFile(File file) {
        return Uri.parse(file.toURI().toASCIIString());
    }
}
