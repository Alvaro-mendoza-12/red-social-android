package conectate.com.presentation.messages

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local fake thumbnails; does not inspect the user's gallery or session. */
@RunWith(AndroidJUnit4::class)
class MessageGalleryAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photosExposeSelectionAndKeepTheContinueActionReachable() {
        val item = fakePhoto()
        val selected = mutableStateListOf<DeviceMedia>()
        var continued = false
        compose.setContent {
            MaterialTheme {
                MessageGalleryStep(listOf(item), "all", {}, setOf("image", "video"),
                    selected, false, true, {}, { selected.add(it) }, "Enviar foto o video", { continued = true })
            }
        }
        val photo = compose.onNodeWithContentDescription("Foto 1")
        photo.assertIsNotSelected().performClick().assertIsSelected()
        val next = compose.onNodeWithContentDescription("Continuar")
        next.assertIsDisplayed().performClick()
        assertTrue(continued)
        assertEquals(1, selected.size)
    }

    @Test fun filtersWrapOnANarrowScreenWithDoubleTextSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        MessageGalleryStep(listOf(fakePhoto()), "all", {}, setOf("image", "video"),
                            emptyList(), false, true, {}, {}, "Enviar foto o video", {})
                    }
                }
            }
        }
        listOf("Todo", "Fotos", "Videos").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Foto 1").assertIsDisplayed().assertHasClickAction()
    }

    private fun fakePhoto() = DeviceMedia(1, Uri.parse("file:///__novvin_qa_missing_thumbnail.png"), "image", 0, 0)
}
