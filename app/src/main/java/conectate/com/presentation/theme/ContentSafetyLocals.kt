package conectate.com.presentation.theme

import androidx.compose.runtime.staticCompositionLocalOf
import conectate.com.data.local.prefs.SensitiveContentMode

val LocalSensitiveContentMode = staticCompositionLocalOf { SensitiveContentMode.BLUR }
