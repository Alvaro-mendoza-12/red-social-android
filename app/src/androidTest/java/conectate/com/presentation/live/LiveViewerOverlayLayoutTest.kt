package conectate.com.presentation.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.LiveGuestAccessState
import conectate.com.data.model.LiveGuestRequestStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveViewerOverlayLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun portraitControlsRemainAboveAComposerIncludingNavigationSpace() {
        compose.setContent { MaterialTheme {
            Box(Modifier.size(360.dp, 650.dp)) {
                LiveViewerOverlayLayout(liveMetrics(360.dp, 650.dp),
                    chat = { Box(Modifier.size(180.dp, 100.dp).testTag("chat")) },
                    actions = { Column(Modifier.testTag("actions")) { repeat(5) { Text("Más", Modifier.height(54.dp)) } } },
                    composer = { Box(Modifier.fillMaxWidth().height(112.dp).testTag("composer")) },
                )
            }
        } }
        assertTrue(bounds("actions").bottom < bounds("composer").top)
        assertTrue(bounds("chat").bottom < bounds("composer").top)
        assertTrue(bounds("actions").top >= 0)
    }

    @Test fun composerResizeMovesTheControlsInTheSameLayoutPass() {
        val height = mutableIntStateOf(80)
        compose.setContent { MaterialTheme {
            Box(Modifier.size(360.dp, 600.dp)) {
                LiveViewerOverlayLayout(liveMetrics(360.dp, 600.dp),
                    chat = { Box(Modifier.size(180.dp, 70.dp).testTag("chat")) },
                    actions = { Box(Modifier.size(60.dp, 200.dp).testTag("actions")) },
                    composer = { Box(Modifier.fillMaxWidth().height(height.intValue.dp).testTag("composer")) },
                )
            }
        } }
        val before = bounds("actions").bottom
        compose.runOnIdle { height.intValue = 150 }
        assertTrue(bounds("actions").bottom < before)
        assertTrue(bounds("actions").bottom < bounds("composer").top)
    }

    @Test fun landscapeOrdersChatThenActionsThenComposerWithLargeText() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) { MaterialTheme {
            Box(Modifier.size(600.dp, 320.dp)) {
                LiveViewerOverlayLayout(liveMetrics(600.dp, 320.dp),
                    chat = { Box(Modifier.size(220.dp, 40.dp).testTag("chat")) },
                    actions = { Box(Modifier.fillMaxWidth().height(70.dp).testTag("actions")) },
                    composer = { Box(Modifier.fillMaxWidth().height(100.dp).testTag("composer")) },
                )
            }
        } } }
        assertTrue(bounds("chat").bottom < bounds("actions").top)
        assertTrue(bounds("actions").bottom < bounds("composer").top)
    }

    @Test fun closedRequestsExplainTheStateWithoutAnUnavailableSubmitButton() {
        compose.setContent { MaterialTheme {
            GuestRequestSheet(LiveGuestAccessState(), false, {}, {}, {})
        } }
        compose.onNodeWithText("Solicitudes cerradas").assertExists()
        compose.onNodeWithText("El creador ha cerrado las solicitudes para unirse.").assertExists()
        compose.onNodeWithText("Solicitar unirme").assertDoesNotExist()
    }

    @Test fun existingRequestCanBeCanceledEvenAfterTheHostClosesNewRequests() {
        compose.setContent { MaterialTheme {
            GuestRequestSheet(LiveGuestAccessState(status = LiveGuestRequestStatus.PENDING), false, {}, {}, {})
        } }
        compose.onNodeWithText("Cancelar solicitud").assertIsEnabled()
    }
}
