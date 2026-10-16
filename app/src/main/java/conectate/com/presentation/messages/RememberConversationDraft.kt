package conectate.com.presentation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.local.ConversationDraftStore

@Composable
internal fun rememberConversationDraft(owner: String?, scope: String, room: String): String {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ConversationDraftStore(context) }
    val flow = remember(owner, scope, room) { store.observe(owner, scope, room) }
    val value by flow.collectAsState(initial = store.read(owner, scope, room))
    return value
}
