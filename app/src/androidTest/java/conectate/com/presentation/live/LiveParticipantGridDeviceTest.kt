package conectate.com.presentation.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.LiveGuestParticipant
import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveBattleStatus
import conectate.com.data.livekit.LiveKitRoomBridge
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.RendererCommon

/** Layout fixtures contain no accounts, network media or other people's data. */
@RunWith(AndroidJUnit4::class)
class LiveParticipantGridDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun show(guests: Int) {
        compose.setContent { MaterialTheme {
            LiveVideoLayout(null,"Anfitrión","qa-host",null,false,emptyMap(),emptyList(),{_,_->false},{_,_->},
                (1..guests).map { LiveGuestParticipant("qa-socket-$it","qa-user-$it","Invitado $it") },
                null,RendererCommon.ScalingType.SCALE_ASPECT_FILL,Modifier.fillMaxSize())
        } }
    }
    @Test fun sevenPeopleKeepEveryAvatarVisibleInSeparateCells() { verify(6) }
    @Test fun ninePeopleKeepEveryAvatarVisibleInSeparateCells() { verify(8) }
    @Test fun ninePeopleAndBattleRemainVisibleInCompactFullScreen() { verifyFrame(8, true) }
    @Test fun sevenPeopleAndControlsRemainVisibleInFullScreen() { verifyFrame(6, false) }
    @Test fun voiceOnlySfuParticipantKeepsItsOwnIdentityAndAvatarCell() {
        compose.setContent { MaterialTheme {
            LiveVideoLayout(null,"Anfitrión","qa-host",null,false,emptyMap(),emptyList(),{_,_->false},{_,_->},
                listOf(LiveGuestParticipant("qa-socket","qa-member","Miembro")),
                null,RendererCommon.ScalingType.SCALE_ASPECT_FILL,Modifier.fillMaxSize(),
                listOf(LiveKitRoomBridge.RemoteParticipant("qa-member","Miembro"),
                    LiveKitRoomBridge.RemoteParticipant("qa-voice","Solo voz")))
        } }
        compose.onNodeWithTag("live_participant_2").assertIsDisplayed()
        compose.onNodeWithTag("live_participant_3").assertDoesNotExist()
        compose.onAllNodesWithText("Solo voz").onFirst().assertIsDisplayed()
    }
    private fun verifyFrame(guests: Int, battle: Boolean) {
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 620.dp)) {
                LiveExperienceFrame(null,"Anfitrión","qa-host",null,false,emptyMap(),emptyList(),emptyList(),
                    {_,_->false},{_,_->},
                    (1..guests).map { LiveGuestParticipant("qa-socket-$it","qa-user-$it","Invitado $it") },
                    null,RendererCommon.ScalingType.SCALE_ASPECT_FILL,emptyList(),emptyList(),
                    LiveBattleState(status=if(battle) LiveBattleStatus.ACTIVE else LiveBattleStatus.NONE,
                        opponentName="Invitado 1",hostScore=12,opponentScore=4,startedAtMs=System.currentTimeMillis()),
                    top={ Text("Directo de prueba",Modifier.height(48.dp).testTag("qa-live-header")) },
                    event={},center={},actions={ metrics ->
                        assertTrue("Collaboration controls must use the bottom row",metrics.horizontalActions)
                        Text("Regalo · Invitado · Compartir · Más",Modifier.height(48.dp).testTag("qa-live-actions"))
                    },bottom={ Text("Comentar",Modifier.height(40.dp).testTag("qa-live-composer")) })
            }
        } }
        val cells=(0..guests).map { compose.onNodeWithTag("live_participant_$it").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot }
        val overlays=listOf("qa-live-header","qa-live-actions","qa-live-composer").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        cells.forEach { cell ->
            assertTrue("Every compact cell must have useful height",cell.height>=24)
            overlays.forEach { assertFalse("Controls cover a participant",cell.overlaps(it)) }
        }
        if(battle) {
            val score=compose.onNodeWithText("Anfitrión en ventaja").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            cells.forEach { assertFalse("Battle score covers a participant",it.overlaps(score)) }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        File(folder,"live-layout-${guests+1}-people.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    private fun verify(guests: Int) {
        show(guests)
        val cells=(0..guests).map { index ->
            compose.onNodeWithTag("live_participant_$index").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        cells.forEach { assertTrue(it.width>0);assertTrue(it.height>0) }
        cells.forEachIndexed { index,cell -> cells.drop(index+1).forEach { other ->
            assertFalse("Participant cells overlap",cell.overlaps(other))
        } }
        (1..guests).forEach { compose.onAllNodesWithText("Invitado $it").onFirst().assertIsDisplayed() }
    }
}
