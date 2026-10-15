package conectate.com.presentation.experience

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.raffles.*
import conectate.com.presentation.raffles.CreateRaffleForm
import conectate.com.presentation.raffles.RaffleWheel
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import conectate.com.presentation.raffles.RafflesUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RaffleRoundReviewUiTest {
    @get:Rule val compose=createComposeRule()
    private val members=List(5) { RaffleParticipant("member-$it", userId="member-$it", name="Persona ${it+1}") }
    private val raffle=Raffle(id="raffle",kind="group",roomId="room",name="Sorteo",creatorId="owner",creatorName="Dueño",
        createdAt="2026-10-08",updatedAt="2026-10-08",status="completed",version=1,participantCount=4,roundCount=1)
    private fun state(loaded:Boolean)=RafflesUiState(accountId="qa",detail=RaffleDetail(raffle,canManage=true,participants=members.take(4)),
        members=if(loaded)members else members.take(4),totalMembers=if(loaded)5 else 4,membersComplete=loaded,membersLoading=!loaded,canCreate=true,loading=false)
    private fun confirmation():SemanticsNodeInteraction {
        val label="Confirmar participantes y preparar ronda"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }
    private fun image(name:String):ByteArray {
        val bytes=ByteArrayOutputStream().use { stream ->
            compose.onNodeWithTag("raffle_wheel").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)
            stream.toByteArray()
        }
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"experience-audit").apply {mkdirs()}
        File(directory,name).writeBytes(bytes)
        return bytes
    }
    @Test fun wheelChangesFramesAndFinishesOnlyAfterFourSecondDeceleration() {
        compose.mainClock.autoAdvance=false
        var finished=0
        compose.setContent {MaterialTheme {RaffleWheel(members,winnerId=members[2].id,animate=true,onAnimationFinished={finished++})}}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        val first=image("raffle-spin-start.png")
        compose.mainClock.advanceTimeBy(750);compose.waitForIdle()
        val moving=image("raffle-spin-moving.png")
        assertFalse(first.contentEquals(moving));assertEquals(0,finished)
        compose.mainClock.advanceTimeBy(2000);compose.waitForIdle();assertEquals(0,finished)
        compose.mainClock.advanceTimeBy(1800);compose.waitForIdle()
        val final=image("raffle-spin-final.png")
        assertFalse(moving.contentEquals(final));assertEquals(1,finished)
        compose.mainClock.autoAdvance=true
    }
    @Test fun fifthMemberAppearsBeforeExplicitConfirmationAndNeverAutoStarts() {
        var selection:List<String>?=null;var auto:Boolean?=null
        compose.setContent { MaterialTheme { CreateRaffleForm(state(true),{_,_,selected,_,start->selection=selected;auto=start},roundReview=true) } }
        compose.waitForIdle();assertNull(selection)
        compose.onNodeWithTag("raffle_wheel").assertContentDescriptionEquals("Ruleta con 5 participantes. El servidor determina los resultados.")
        confirmation().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(members.map {it.id},selection);assertEquals(false,auto) }
    }
    @Test fun incompleteMembershipDisablesConfirmationUntilAllFiveAreLoaded() {
        val current=mutableStateOf(state(false));var confirmations=0
        compose.setContent { MaterialTheme { CreateRaffleForm(current.value,{_,_,_,_,_->confirmations++},roundReview=true) } }
        confirmation().assertIsNotEnabled()
        compose.runOnIdle { current.value=state(true) }
        compose.waitForIdle()
        confirmation().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,confirmations)}
    }
}
