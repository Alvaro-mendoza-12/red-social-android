package conectate.com.presentation.messages

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.localfallback.CollectionMembershipPolicy
import conectate.com.data.model.User
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class CollectionMembershipControlsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/$name")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val rootNode = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
            compose.onNode(isDialog()) else compose.onRoot()
        file.outputStream().use { check(rootNode.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun addingNeedsConfirmationAndDoesNotSharePriorHistoryByDefault() {
        var added = false
        var from: String? = "not-called"
        compose.setContent { MaterialTheme {
            CollectionAddMemberDialog({}, {}, listOf(User(id="pedro", full_name="Pedro")),
                CollectionMembershipPolicy(canAddMembers=true), false, null,
                { _,start,_ -> added=true;from=start })
        } }
        compose.onNode(hasText("Añadir") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Pedro").performClick()
        assertFalse(added)
        compose.onNodeWithText("Verá los mensajes nuevos desde que se una.").assertIsDisplayed()
        screenshot("membership-confirm-no-history.png")
        compose.onNode(hasText("Añadir") and hasClickAction()).performClick()
        assertTrue(added); assertNull(from)
    }
    @Test fun sharingRequiresChoosingBothDates() {
        compose.setContent { MaterialTheme {
            CollectionAddMemberDialog({}, {}, listOf(User(id="pedro", full_name="Pedro")),
                CollectionMembershipPolicy(canAddMembers=true), false, null,{ _,_,_ -> fail("Unconfirmed history") })
        } }
        compose.onNodeWithText("Pedro").performClick()
        compose.onNode(isToggleable()).performClick()
        compose.onNode(hasText("Añadir") and hasClickAction()).assertIsNotEnabled()
        screenshot("membership-share-range-required.png")
    }
    @Test fun onlyAdministratorSeesTheMembershipSetting() {
        compose.setContent { MaterialTheme {
            CollectionMembershipSettings(CollectionMembershipPolicy(canManagePolicy=false),false,{ fail("Not an administrator") })
        } }
        compose.onNodeWithText("Permitir que los miembros añadan personas").assertDoesNotExist()
    }
    @Test fun administratorCanDisableMemberAdditions() {
        val enabled = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme {
            CollectionMembershipSettings(CollectionMembershipPolicy(membersCanAdd=enabled.value,canManagePolicy=true),false,{ enabled.value=it })
        } }
        compose.onNode(isToggleable()).performClick(); assertFalse(enabled.value)
        screenshot("membership-admin-permission.png")
    }
    @Test fun joiningNoticeIsNeutralAndHasNoMessageActions() {
        compose.setContent { MaterialTheme { CollectionSystemNotice("Iván añadió a Pedro") } }
        compose.onNodeWithText("Iván añadió a Pedro").assertIsDisplayed().assertHasNoClickAction()
        screenshot("membership-system-notice.png")
    }
}
