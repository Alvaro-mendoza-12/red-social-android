package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConversationUpdateIsolationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewportReceiptsUpdateWithoutRecomposingTheConversation() {
        val messages = (0..300).map { Message(id = "row-$it", sender_id = "other") }
        val compositions = AtomicInteger()
        val visible = AtomicReference(emptySet<String>())
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                SideEffect { compositions.incrementAndGet() }
                state = rememberConversationListState()
                ObserveVisibleConversationMessages(messages, state, false, visible::set)
                LazyColumn(state = state, modifier = Modifier.height(240.dp).testTag("history")) {
                    items(messages, key = Message::id) { Text(it.id, Modifier.height(48.dp)) }
                }
            }
        }
        compose.waitUntil(5_000) { "row-0" in visible.get() }
        val before = compositions.get()
        compose.onNodeWithTag("history").performScrollToIndex(200)
        compose.waitUntil(5_000) { "row-200" in visible.get() }
        assertEquals(before, compositions.get())
        assertTrue("Old viewport must stop receiving read receipts", "row-0" !in visible.get())
    }

    private class Playback {
        val id = MutableStateFlow<String?>("active")
        val playing = MutableStateFlow(true)
        val progress = MutableStateFlow(0f)
        val label = MutableStateFlow("0:00")
        val totals = MutableStateFlow(mapOf("active" to 30_000L, "idle" to 20_000L))
        val speed = MutableStateFlow(1f)
    }

    @Composable
    private fun AudioRow(id: String, type: String, player: Playback, compositions: AtomicInteger) {
        val audio = rememberChatMessageAudioState(id, type, "0:20", player.id, player.playing,
            player.progress, player.label, player.totals, player.speed)
        SideEffect { compositions.incrementAndGet() }
        Text("$id: ${audio.progress} ${audio.duration}")
    }

    @Test fun playbackClockOnlyRecomposesThePlayingAudioRow() {
        val player = Playback()
        val conversation = AtomicInteger()
        val active = AtomicInteger()
        val idle = AtomicInteger()
        val text = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                SideEffect { conversation.incrementAndGet() }
                Column {
                    AudioRow("active", "audio", player, active)
                    AudioRow("idle", "audio", player, idle)
                    AudioRow("text", "text", player, text)
                }
            }
        }
        compose.waitForIdle()
        val before = listOf(conversation.get(), active.get(), idle.get(), text.get())
        repeat(12) { index ->
            compose.runOnIdle {
                player.progress.value = (index + 1) / 20f
                player.label.value = "0:${index + 1}"
            }
            compose.waitForIdle()
        }
        assertEquals(before[0], conversation.get())
        assertTrue(active.get() > before[1])
        assertEquals(before[2], idle.get())
        assertEquals(before[3], text.get())
        compose.runOnIdle { player.id.value = "idle" }
        compose.waitForIdle()
        val stoppedCount = active.get()
        compose.runOnIdle { player.progress.value = .9f; player.label.value = "0:18" }
        compose.waitForIdle()
        assertEquals("The old audio row must stop observing the clock", stoppedCount, active.get())
    }
}
