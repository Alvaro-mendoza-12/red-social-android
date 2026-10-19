package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import conectate.com.presentation.auth.PortraitQrCaptureActivity
import conectate.com.presentation.navigation.Screen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QrCloudRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inboxScannerUsesRootFromInsideTheTabs() {
        compose.setContent {
            MaterialTheme {
                val root = rememberNavController()
                NavHost(root, startDestination = "home") {
                    composable("home") {
                        val tabs = rememberNavController()
                        NavHost(tabs, startDestination = "messages") {
                            composable("messages") { MessageWebScannerButton(root) { fail("Valid root scanner route") } }
                        }
                    }
                    composable(Screen.WebLoginScanner.route, arguments = listOf(
                        navArgument("pairId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("nonce") { type = NavType.StringType; nullable = true; defaultValue = null },
                    )) { Text("Escáner abierto") }
                }
            }
        }
        compose.onNodeWithTag("message-web-scanner").performClick()
        compose.onNodeWithText("Escáner abierto").assertIsDisplayed()
    }

    @Test fun unavailableScannerKeepsTheInboxOpen() {
        var errors by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                val tabs = rememberNavController()
                NavHost(tabs, startDestination = "messages") {
                    composable("messages") {
                        MessageWebScannerButton(tabs) { errors++ }
                        Text("Errores: $errors")
                    }
                }
            }
        }
        compose.onNodeWithTag("message-web-scanner").performClick()
        compose.onNodeWithText("Errores: 1").assertIsDisplayed()
        compose.onNodeWithTag("message-web-scanner").assertIsEnabled()
    }

    @Test fun cloudFramesMoveAndPauseWithoutUsingAndroidAnimatorScale() {
        compose.mainClock.autoAdvance = false
        var moving by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("cloud-stage")) {
                    CloudAnswerBubble("Una respuesta", 0, moving, onClick = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val before = compose.onNodeWithTag("cloud-stage").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(1700)
        compose.waitForIdle()
        val after = compose.onNodeWithTag("cloud-stage").captureToImage().toPixelMap()
        var changed = 0
        for (x in 0 until before.width step 2) for (y in 0 until before.height step 2) if (before[x,y] != after[x,y]) changed++
        assertTrue("Cloud pixels must move with elapsed frame time", changed > 40)
        compose.runOnIdle { moving = false }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val paused = compose.onNodeWithTag("cloud-stage").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        val stillPaused = compose.onNodeWithTag("cloud-stage").captureToImage().toPixelMap()
        for (x in 0 until paused.width step 4) for (y in 0 until paused.height step 4) assertEquals(paused[x,y], stillPaused[x,y])
    }

    @Test fun explicitMotionChoiceIsSharedAndIsolatedByAccount() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("question_cloud_motion", 0)
        val keys = listOf("choice:motion-regression-a", "choice:motion-regression-b")
        preferences.edit().apply { keys.forEach { remove(it) } }.commit()
        try {
            var account by mutableStateOf("motion-regression-a")
            compose.setContent {
                MaterialTheme {
                    val (choice, setChoice) = rememberQuestionCloudMotion(account)
                    val (otherCard, _) = rememberQuestionCloudMotion(account)
                    Column {
                        Text("Primaria:${choice.name}")
                        Text("Secundaria:${otherCard.name}")
                        Button(onClick = { setChoice(CloudMotionChoice.ON) }) { Text("Activar") }
                    }
                }
            }
            compose.onNodeWithText("Activar").performClick()
            compose.onNodeWithText("Primaria:ON").assertIsDisplayed()
            compose.onNodeWithText("Secundaria:ON").assertIsDisplayed()
            compose.runOnIdle { account = "motion-regression-b" }
            compose.onNodeWithText("Primaria:AUTO").assertIsDisplayed()
            compose.runOnIdle { account = "motion-regression-a" }
            compose.onNodeWithText("Primaria:ON").assertIsDisplayed()
        } finally {
            preferences.edit().apply { keys.forEach { remove(it) } }.commit()
        }
    }

    @Test fun cameraTorchUsesTheInitializedScannerWithoutSearchingTheWindowTree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH))
        ActivityScenario.launch<PortraitQrCaptureActivity>(android.content.Intent(context, PortraitQrCaptureActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.window.decorView.findViewWithTag<android.widget.Button>("qr-torch-toggle")
                assertNotNull(button)
                button.performClick()
                assertEquals("Apagar linterna", button.text.toString())
                button.performClick()
                assertEquals("Encender linterna", button.text.toString())
                assertFalse(activity.isFinishing)
            }
        }
    }
}
