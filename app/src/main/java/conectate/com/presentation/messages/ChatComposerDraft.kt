package conectate.com.presentation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Read the rapidly changing draft here so typing cannot invalidate the conversation timeline. */
@Composable
internal fun ChatComposerDraft(text: StateFlow<String>, content: @Composable (String) -> Unit) {
    val draft by text.collectAsStateWithLifecycle()
    content(draft)
}
