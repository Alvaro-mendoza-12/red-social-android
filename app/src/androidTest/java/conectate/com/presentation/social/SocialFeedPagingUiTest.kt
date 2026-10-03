package conectate.com.presentation.social

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialFeedPagingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewportLoadsAgainWhenAFilteredPageAddsNoVisibleRows() {
        var generation by mutableIntStateOf(0)
        var loading by mutableStateOf(false)
        var requests = 0
        compose.setContent {
            val state = rememberLazyListState()
            MaterialTheme {
                SocialFeedPagingEffect(state, true, generation < 2, false, loading, false, 0) {
                    requests++; loading = true
                }
                LaunchedEffect(loading) {
                    if (loading) { generation++; loading = false }
                }
                LazyColumn(Modifier.height(400.dp), state = state) {
                    items(3) { Text("Publicación $it", Modifier.height(60.dp)) }
                }
            }
        }
        compose.waitUntil(5000) { generation == 2 }
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun refreshReturnsToTheStartWithContentStillPresent() {
        var reset by mutableLongStateOf(0L)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                SocialFeedPagingEffect(list, true, false, false, false, false, reset) {}
                LazyColumn(Modifier.height(300.dp).testTag("feed"), state = list) {
                    items(40) { Text("Publicación $it", Modifier.height(80.dp)) }
                }
            }
        }
        compose.onNodeWithTag("feed").performScrollToIndex(20)
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex >= 20); reset++ }
        compose.waitUntil(5000) { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }
        compose.onNodeWithText("Publicación 0").assertIsDisplayed()
    }

    @Test fun anErrorPreventsAutomaticRequestsUntilExplicitRetryClearsIt() {
        var error by mutableStateOf(true)
        var requests = 0
        compose.setContent {
            val list = rememberLazyListState()
            SocialFeedPagingEffect(list, true, true, false, false, error, 0) { requests++ }
            LazyColumn(Modifier.height(400.dp), state = list) {
                items(3) { Text("Publicación $it", Modifier.height(60.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, requests); error = false }
        compose.waitUntil(5000) { requests == 1 }
    }
}
