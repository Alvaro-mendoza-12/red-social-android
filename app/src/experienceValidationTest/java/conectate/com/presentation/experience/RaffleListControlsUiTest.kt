package conectate.com.presentation.experience

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.raffles.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RaffleListControlsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun eightyPeopleSupportFourTenAllCustomAndWholeListSearch() {
        val original = List(80) { "Persona ${it + 1}" }
        compose.setContent { MaterialTheme {
            var limit by remember { mutableStateOf("10") }; var search by remember { mutableStateOf("") }
            val window = raffleListWindow(original, search, raffleDisplayLimit(limit)) { it }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                RaffleListControls(limit,search,{limit=it},{search=it})
                Text("${window.items.size}/${window.matching}/${window.total}", Modifier.testTag("window_count"))
                Text(window.items.joinToString("\n"), Modifier.testTag("window_names"))
            }
        } }
        compose.onNodeWithTag("window_count").assertTextEquals("10/80/80")
        compose.onNodeWithTag("raffle_limit_4").performClick(); compose.onNodeWithTag("window_count").assertTextEquals("4/80/80")
        compose.onNodeWithTag("raffle_limit_all").performClick(); compose.onNodeWithTag("window_count").assertTextEquals("80/80/80")
        compose.onNodeWithTag("raffle_limit_custom").performTextReplacement("7"); compose.onNodeWithTag("window_count").assertTextEquals("7/80/80")
        compose.onNodeWithTag("raffle_list_search").performTextReplacement("Persona 80"); compose.onNodeWithTag("window_count").assertTextEquals("1/1/80")
        compose.onNodeWithTag("window_names").assertTextEquals("Persona 80")
        assertEquals(80, original.size)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val folder=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"experience-audit").apply { mkdirs() }
        File(folder,"raffle-controls.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
}
