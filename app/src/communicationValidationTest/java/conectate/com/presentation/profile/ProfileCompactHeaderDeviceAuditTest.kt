package conectate.com.presentation.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.Badge
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.BadgeRow
import conectate.com.presentation.components.CircularProfileImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ProfileCompactHeaderDeviceAuditTest {
    @get:Rule
    val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertCompleteUnclippedText(text: String) {
        val layout = textLayout(text)
        val diagnostic = "text=$text size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}"
        // A wrap-content Text can retain its wider paragraph measurement in
        // GetTextLayoutResult. hasVisualOverflow then compares that container
        // with the final intrinsic width, even when every glyph is inside it.
        // Verify all characters, line bounds and truncation directly instead.
        assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
        assertEquals(diagnostic, text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        for (line in 0 until layout.lineCount) {
            assertFalse(diagnostic, layout.isLineEllipsized(line))
            assertTrue(diagnostic, layout.getLineLeft(line) >= -1f)
            assertTrue(diagnostic, layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue(diagnostic, layout.getLineTop(line) >= -1f)
            assertTrue(diagnostic, layout.getLineBottom(line) <= layout.size.height + 1f)
        }
        for (character in text.indices) {
            val glyph = layout.getBoundingBox(character)
            assertTrue("$diagnostic glyph=$glyph", glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
            assertTrue("$diagnostic glyph=$glyph", glyph.top >= -1f && glyph.bottom <= layout.size.height + 1f)
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/$name")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use {
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun shortNameVerifiedMarkAndCountersShareTheSameRow() {
        var followers = 0
        var following = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        ProfileNameAndCounts("Alvaro", 123, 456, { followers++ }, { following++ }, isVerified = true)
                    }
                }
            }
        }
        val name = compose.onNodeWithText("Alvaro").fetchSemanticsNode().boundsInRoot
        val verified = compose.onNodeWithContentDescription("Verificado").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithContentDescription("123 Seguidores").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription("456 Siguiendo").fetchSemanticsNode().boundsInRoot
        assertTrue(name.right <= verified.left)
        assertTrue(verified.right <= first.left)
        assertTrue(first.right <= second.left)
        assertTrue(verified.top >= name.top && verified.bottom <= name.bottom + 1f)
        assertTrue(kotlin.math.abs(first.top - name.top) <= 1f)
        screenshot("profile-compact-inline-verified.png")
        assertCompleteUnclippedText("Alvaro")
        compose.onNodeWithContentDescription("123 Seguidores").performClick()
        compose.onNodeWithContentDescription("456 Siguiendo").performClick()
        assertEquals(1, followers)
        assertEquals(1, following)
        screenshot("profile-compact-inline-verified.png")
    }

    @Test
    fun twentyCharacterNameUsesItsOwnRowInsteadOfEllipsis() {
        val nameText = "NombreDe20Caracteres"
        assertEquals(20, nameText.length)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(240.dp)) {
                        ProfileNameAndCounts(nameText, 123456, 678901, {}, {}, isVerified = true)
                    }
                }
            }
        }
        val name = compose.onNodeWithText(nameText).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val verified = compose.onNodeWithContentDescription("Verificado").fetchSemanticsNode().boundsInRoot
        val followers = compose.onNodeWithContentDescription("123456 Seguidores").fetchSemanticsNode().boundsInRoot
        assertTrue(name.right <= verified.left)
        assertTrue(followers.top >= maxOf(name.bottom, verified.bottom))
        val layout = textLayout(nameText)
        assertFalse("The complete twenty character name must remain readable", layout.hasVisualOverflow)
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
        screenshot("profile-compact-name-20-characters.png")
    }

    @Test
    fun twoHundredPercentFontKeepsCompleteNameLabelsAndSeparateClickTargets() {
        val nameText = "NombreDe20Caracteres"
        var followersClicked = 0
        var followingClicked = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(240.dp)) {
                        ProfileNameAndCounts(nameText, 123456, 678901, { followersClicked++ }, { followingClicked++ }, isVerified = true)
                    }
                }
            }
        }
        val name = compose.onNodeWithText(nameText).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val verified = compose.onNodeWithContentDescription("Verificado").fetchSemanticsNode().boundsInRoot
        val followers = compose.onNodeWithContentDescription("123456 Seguidores").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val following = compose.onNodeWithContentDescription("678901 Siguiendo").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.right <= verified.left)
        assertTrue(verified.top >= name.top && verified.bottom <= name.bottom)
        assertTrue(followers.top >= name.bottom)
        assertTrue(followers.right <= following.left || followers.bottom <= following.top)
        assertFalse(textLayout(nameText).hasVisualOverflow)
        for (label in listOf("Seguidores", "Siguiendo")) {
            val layout = textLayout(label)
            assertEquals("Counter labels must keep the complete word", 1, layout.lineCount)
            assertFalse(layout.hasVisualOverflow)
        }
        compose.onNodeWithContentDescription("123456 Seguidores").performClick()
        compose.onNodeWithContentDescription("678901 Siguiendo").performClick()
        assertEquals(1, followersClicked)
        assertEquals(1, followingClicked)
        screenshot("profile-compact-200-percent-font.png")
    }

    @Test
    fun avatarOverlapsCoverAndBothVerifiedMarksRemainWhileActionsFollowMeasuredHeader() {
        var openedAvatar = 0
        var openedCover = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        ProfileIdentityHeader(
                            modifier = Modifier.testTag("header"),
                            cover = {
                                Box(Modifier.fillMaxWidth().height(200.dp).testTag("cover").clickable { openedCover++ })
                            },
                            avatar = {
                                Box(Modifier.testTag("avatar_action").clickable { openedAvatar++ }) {
                                    CircularProfileImage(null, Modifier.testTag("avatar"), size = 84.dp, isVerified = true, fallbackName = "Alvaro")
                                }
                            },
                            identity = {
                                ProfileNameAndCounts("Alvaro", 4, 1, {}, {}, isVerified = true)
                                ProfilePublicIdentity("alvaro", "N12345", Color(0xFF6200EE), {}, isAdmin = true)
                                Row {
                                    BadgeRow(
                                        userName = null,
                                        badges = listOf(Badge("founder", "Fundador", null, "★", "#6200EE", "manual")),
                                        includeAdmin = false,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            },
                        )
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("actions")) { Text("Editar") }
                        }
                        Text("Videos  Publicaciones  Compartidos", Modifier.testTag("tabs"))
                    }
                }
            }
        }
        val scale = compose.density.density
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag("avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Alvaro").fetchSemanticsNode().boundsInRoot
        val role = compose.onNodeWithText("Fundador", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithText("@alvaro").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
        assertEquals("The avatar must retain its cover overlap", cover.bottom - 50f * scale, avatar.top, 1f)
        assertTrue("The name must sit below the cover", name.top >= cover.bottom + 8f * scale - 1f)
        assertTrue(name.left >= avatar.right + 12f * scale - 1f)
        assertEquals("The handle follows the same identity alignment", name.left, handle.left, 1f)
        assertTrue(role.left >= name.left)
        assertEquals("No 50 dp displaced space may remain before actions", header.bottom + 8f * scale, actions.top, 1f)
        compose.onAllNodesWithContentDescription("Verificado", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithTag("tabs").assertIsDisplayed()
        // Press the avatar at y=21 dp, within the portion that overlaps the cover.
        compose.onNodeWithTag("avatar_action").performTouchInput { click(Offset(width / 2f, height / 4f)) }
        assertEquals(1, openedAvatar)
        assertEquals("The overlapping avatar must receive the tap before the cover", 0, openedCover)
        compose.onNodeWithTag("cover").performTouchInput { click(Offset(width * 0.8f, height / 2f)) }
        assertEquals(1, openedCover)
        screenshot("profile-compact-header-avatar-and-roles.png")
    }

    @Test
    fun compactHandleAndIdWrapSafelyAndKeepIdDialogAccessible() {
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(200.dp).testTag("identity")) {
                        ProfilePublicIdentity("NombreDe20Caracteres", "N12345", Color(0xFF6200EE), { opened++ }, isAdmin = true)
                    }
                }
            }
        }
        val parent = compose.onNodeWithTag("identity").fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithText("@NombreDe20Caracteres").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val id = compose.onNodeWithContentDescription("Ver ID del perfil").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(handle.right <= parent.right + 1f)
        assertTrue(id.right <= parent.right + 1f)
        assertTrue(handle.right <= id.left || handle.bottom <= id.top)
        assertTrue("ID touch height=${id.height}", id.height >= 48f * compose.density.density - 1f)
        val admin = compose.onNodeWithText("ADMIN", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(admin.right <= parent.right + 1f)
        assertTrue(id.right <= admin.left || id.bottom <= admin.top)
        assertCompleteUnclippedText("#N12345")
        compose.onNodeWithContentDescription("Ver ID del perfil").performClick()
        assertEquals(1, opened)
        screenshot("profile-compact-handle-id-large-font.png")
    }

    @Test fun handleIdAndAdminUseOneRowAtNormalPhoneWidth() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(275.dp).testTag("identity")) {
                        ProfilePublicIdentity("alvaro", "40213", Color(0xFF6200EE), {}, isAdmin = true)
                    }
                }
            }
        }
        val handle = compose.onNodeWithText("@alvaro").fetchSemanticsNode().boundsInRoot
        val id = compose.onNodeWithContentDescription("Ver ID del perfil").fetchSemanticsNode().boundsInRoot
        val admin = compose.onNodeWithText("ADMIN", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag("identity").fetchSemanticsNode().boundsInRoot
        assertTrue(handle.right <= id.left && id.right <= admin.left)
        assertTrue(kotlin.math.abs(handle.center.y - admin.center.y) <= 1f)
        assertTrue(parent.height <= 48f * compose.density.density + 1f)
        assertCompleteUnclippedText("@alvaro")
        assertCompleteUnclippedText("#40213")
        screenshot("profile-compact-public-identity-admin.png")
    }
}
