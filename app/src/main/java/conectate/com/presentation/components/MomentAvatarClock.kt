package conectate.com.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant

/** One clock per screen, not one polling job per post. Expired rings disappear within a second. */
@Composable
fun rememberMomentAvatarClock(): State<Instant> = produceState(Instant.now()) {
    while (true) { delay(1_000L); value = Instant.now() }
}
