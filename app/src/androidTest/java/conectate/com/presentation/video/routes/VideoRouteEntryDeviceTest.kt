package conectate.com.presentation.video.routes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.MainActivity
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.presentation.navigation.Screen
import conectate.com.ui.TestRuntimeFixture
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Drives the real Videos menu, saved route and single-video destination on the QA emulator. */
class VideoRouteEntryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun videosMenuReopensTheSavedRouteAndReturnsFromItsPlayer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRouteEntryFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountId = UUID.randomUUID().toString()
        val journal = VideoRouteJournal(encryptedVideoRoutePreferences(context))
        TestRuntimeFixture.begin()
        try {
            val preferences = context.getSharedPreferences(TestRuntimeOverrides.PREFS_NAME, Context.MODE_PRIVATE)
            check(preferences.edit().putBoolean("disable_startup_prompts", true).commit())
            TestRuntimeFixture.seedSavedAccountHarness(userId = accountId)
            journal.replace(accountId, null, VideoRoute(
                id = UUID.randomUUID().toString(),
                topic = "Ruta de prueba",
                requestedCount = 3,
                items = listOf(VideoRouteItem("qa-missing-video", "Clip de prueba", "qa-author", "QA")),
            ))
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            ActivityScenario.launch<MainActivity>(intent).use { checkVideoRoute() }
        } finally {
            try {
                journal.erase(accountId)
            } finally {
                TestRuntimeFixture.clear()
            }
        }
    }

    private fun checkVideoRoute() {
        val videosTab = hasTestTag("main-tab-${Screen.Videos.route}")
        awaitNode(videosTab)
        compose.onNode(videosTab).assertIsDisplayed().performClick()
        compose.onNode(videosTab).assertIsSelected()

        val create = "Crear o abrir biblioteca de Videos"
        awaitNode(hasContentDescription(create))
        compose.onNodeWithContentDescription(create).performClick()
        awaitNode(hasText("Rutas con propósito"))
        compose.onNodeWithText("Rutas con propósito").performClick()
        awaitNode(hasText("Ruta de prueba"))

        compose.onNodeWithContentDescription("Opciones de la ruta").performClick()
        compose.onNodeWithText("Crear otra ruta").performClick()
        compose.onNodeWithText("Volver a mi ruta").performScrollTo().performClick()
        awaitNode(hasText("Clip de prueba"))

        compose.onNodeWithText("Abrir video").performScrollTo().performClick()
        awaitNode(hasText("Volver a mi ruta"))
        compose.onNodeWithContentDescription("Volver").performClick()
        awaitNode(hasText("Ruta de prueba"))
        compose.onNodeWithText("Ruta de prueba").performScrollTo().assertIsDisplayed()
    }

    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(20_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
