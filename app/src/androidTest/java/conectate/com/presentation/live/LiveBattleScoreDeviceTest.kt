package conectate.com.presentation.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import org.junit.Rule
import org.junit.Test

class LiveBattleScoreDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun committedScoresChangeTheLeadAndFinalResult() {
        val state=mutableStateOf(LiveBattleState(status=LiveBattleStatus.ACTIVE,
            opponentName="Invitado QA",startedAtMs=System.currentTimeMillis()))
        compose.setContent { MaterialTheme { BattleScoreOverlay(state.value) } }
        compose.onNodeWithText("Batalla empatada").assertIsDisplayed()
        compose.runOnIdle { state.value=state.value.copy(hostScore=12,opponentScore=4) }
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithText("Anfitrión en ventaja").assertIsDisplayed()
        compose.runOnIdle { state.value=state.value.copy(hostScore=12,opponentScore=20) }
        compose.onNodeWithText("Invitado QA en ventaja").assertIsDisplayed()
        compose.runOnIdle { state.value=state.value.copy(status=LiveBattleStatus.FINISHED) }
        compose.onNodeWithText("Ganó Invitado QA").assertIsDisplayed()
        compose.onNodeWithText("FIN").assertIsDisplayed()
    }
}
