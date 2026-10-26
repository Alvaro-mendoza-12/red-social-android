package conectate.com.presentation.components

import android.app.Application
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25], application = Application::class, manifest = Config.NONE)
class ProfileSystemAnimationsTest {
    @Test fun enabledSystemAnimationsAreCompatibleWithBothAndroid7Versions() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(profileSystemAnimationsEnabled(context))
    }

    @Test fun disabledSystemAnimationsKeepProfileEffectsStatic() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(profileSystemAnimationsEnabled(context))
    }
}
