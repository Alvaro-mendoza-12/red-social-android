package conectate.com.widgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NovvinWidgetIntentTest {
    @Test fun `each widget opens its own existing destination with an explicit activity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pending = listOf("messages", "videos", "create-moment", "social").map { destination ->
            val action = widgetDestinationIntent(context, destination, 71)
            val intent = shadowOf(action).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals("conectate://$destination", intent.dataString)
            action
        }
        assertEquals(4, pending.toSet().size)
    }
    @Test(expected = IllegalArgumentException::class) fun `widgets cannot open arbitrary external links`() {
        widgetDestinationIntent(ApplicationProvider.getApplicationContext(), "https://untrusted.test", 72)
    }
}
