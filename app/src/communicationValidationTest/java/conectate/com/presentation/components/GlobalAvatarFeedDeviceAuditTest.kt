package conectate.com.presentation.components

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.feed.FeedSeenHistory
import conectate.com.data.model.Moment
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlobalAvatarFeedDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    private val owner = "10000000-0000-4000-8000-000000000001"
    private class Registrar : AvatarMomentRegistrar {
        val registrations = mutableMapOf<String, String>()
        override fun register(owner: String): String = java.util.UUID.randomUUID().toString().also { registrations[it] = owner }
        override fun unregister(key: String) { registrations.remove(key) }
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/$name")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun authorizedMomentAppearsInEveryMainSectionWithoutAnAvatarPhoto() {
        val registrar = Registrar(); var opened = 0
        val section = mutableStateOf("Mensajes")
        val moment = Moment("moment", owner, "", "image", expiresAt=Instant.now().plusSeconds(3600).toString())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAvatarMoments provides AvatarMomentAccess(mapOf(owner to moment), registrar) { opened++ }) {
                Column { Text(section.value); CircularProfileImage(null, Modifier.testTag("global_avatar"), ownerId=owner, size=60.dp, fallbackName="Novvin") }
            }
        } }
        for (name in listOf("Mensajes","Videos","Social","Juegos","Perfil")) {
            compose.runOnIdle { section.value = name }
            compose.onNodeWithTag("global_avatar").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Momento sin ver")).performClick()
            screenshot("global-avatar-${name.lowercase()}.png")
        }
        compose.runOnIdle { assertEquals(5, opened); assertEquals(listOf(owner), registrar.registrations.values.toList()) }
    }
    @Test fun decorativeMomentRingPreservesTheContainingNavigationAction() {
        val registrar = Registrar(); var opened = 0; var selected = 0
        val moment = Moment("moment", owner, "", "image", expiresAt=Instant.now().plusSeconds(3600).toString())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAvatarMoments provides AvatarMomentAccess(mapOf(owner to moment), registrar) { opened++ }) {
                Column(Modifier.testTag("containing_action").clickable { selected++ }) {
                    CircularProfileImage(null, Modifier.testTag("decorative_avatar"), ownerId=owner, openMomentOnClick=false, fallbackName="Novvin")
                }
            }
        } }
        compose.onNodeWithTag("decorative_avatar", useUnmergedTree=true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Momento sin ver"))
            .assertHasNoClickAction()
        compose.onNodeWithTag("containing_action").performClick()
        compose.runOnIdle { assertEquals(1,selected); assertEquals(0,opened) }
    }
    @Test fun explicitMomentCardAndBottomProfileExceptionsHideTheRing() {
        val registrar=Registrar(); var opened=0
        val moment=Moment("moment",owner,"","image",expiresAt=Instant.now().plusSeconds(3600).toString())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAvatarMoments provides AvatarMomentAccess(mapOf(owner to moment),registrar) { opened++ }) {
                Column {
                    CircularProfileImage(null,Modifier.testTag("moment_card_avatar"),ownerId=owner,showMomentRing=false,fallbackName="Novvin")
                    CircularProfileImage(null,Modifier.testTag("bottom_profile_avatar"),ownerId=owner,showMomentRing=false,fallbackName="Novvin")
                }
            }
        } }
        for(tag in listOf("moment_card_avatar","bottom_profile_avatar")) compose.onNodeWithTag(tag)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)).assertHasNoClickAction()
        compose.runOnIdle { assertEquals(0,opened); assertTrue(registrar.registrations.isEmpty()) }
        screenshot("global-avatar-ring-exceptions.png")
    }
    @Test fun removalOfAuthorizedMomentClearsTheRingAndItsAction() {
        val registrar = Registrar(); var opened = 0
        val visible = mutableStateOf(true)
        val moment = Moment("moment", owner, "", "image", expiresAt=Instant.now().plusSeconds(3600).toString())
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalAvatarMoments provides AvatarMomentAccess(if(visible.value) mapOf(owner to moment) else emptyMap(),registrar) { opened++ }) {
                CircularProfileImage(null, Modifier.testTag("global_avatar"), ownerId=owner, fallbackName="Novvin")
            }
        } }
        compose.onNodeWithTag("global_avatar").assertHasClickAction()
        compose.runOnIdle { visible.value=false }
        compose.onNodeWithTag("global_avatar").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)).assertHasNoClickAction()
        compose.runOnIdle { assertEquals(0,opened) }
    }
    @Test fun seenVideoSurvivesStoreRecreationOnRealAndroidWithoutAProductionAccount() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "offline-native-${java.util.UUID.randomUUID()}"
        val first=FeedSeenHistory(context); val second=FeedSeenHistory(context)
        try {
            first.record(account,"video","video-visible")!!.join()
            assertEquals(setOf("video-visible"), second.snapshot(account,"video"))
            assertTrue(second.snapshot(account+"-other","video").isEmpty())
            assertTrue(second.snapshot(account,"social").isEmpty())
        } finally { first.closeForTest(); second.closeForTest() }
    }
    @Test fun videoAvatarAdvancesAcrossTheFiveMainSectionLabels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val source=File(context.cacheDir,"avatar-native-${java.util.UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("global-avatar-moving-two-seconds.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val section=mutableStateOf("Mensajes")
        val finished=mutableStateOf(false)
        try {
            compose.setContent { MaterialTheme { if(finished.value) Text("Finalizado") else Column {
                Text(section.value)
                CircularProfileImage(android.net.Uri.fromFile(source).toString(), Modifier.testTag("moving_avatar"), size=90.dp, mediaType="video", fallbackName="Novvin")
            } } }
            fun textures(view: View): List<TextureView> = if(view is TextureView) listOf(view) else if(view is ViewGroup) (0 until view.childCount).flatMap { textures(view.getChildAt(it)) } else emptyList()
            fun frame(): String? {
                var digest: String?=null
                instrumentation.runOnMainSync {
                    val views=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    val bitmap=views.flatMap { textures(it.window.decorView) }.firstOrNull { it.isAvailable }?.getBitmap(48,48)
                    bitmap?.let {
                        val pixels=IntArray(48*48);it.getPixels(pixels,0,48,0,0,48,48)
                        if(pixels.any { pixel -> (pixel and 0x00ffffff) != 0 }) digest=pixels.contentHashCode().toString()
                        it.recycle()
                    }
                }
                return digest
            }
            for(name in listOf("Mensajes","Videos","Social","Juegos","Perfil")) {
                compose.runOnIdle { section.value=name }
                var initial: String?=null
                compose.waitUntil(15_000L) { frame()?.also { initial=it } != null }
                compose.waitUntil(5_000L) { frame()?.let { it!=initial } == true }
            }
            screenshot("global-animated-avatar.png")
            compose.runOnIdle { finished.value=true }
            compose.waitForIdle()
        } finally { assertTrue(!source.exists() || source.delete()) }
    }
    @Test fun profileNameAndCountersShareARowWithoutOverlap() {
        var followers=0;var following=0
        compose.setContent { MaterialTheme { Box(Modifier.width(240.dp)) {
            conectate.com.presentation.profile.ProfileNameAndCounts("Alvaro",123,456,{followers++},{following++})
        } } }
        val name=compose.onNodeWithText("Alvaro").fetchSemanticsNode().boundsInRoot
        val first=compose.onNodeWithContentDescription("123 Seguidores").fetchSemanticsNode().boundsInRoot
        val second=compose.onNodeWithContentDescription("456 Siguiendo").fetchSemanticsNode().boundsInRoot
        assertTrue(name.right<=first.left);assertTrue(first.right<=second.left)
        compose.onNodeWithContentDescription("123 Seguidores").performClick()
        compose.onNodeWithContentDescription("456 Siguiendo").performClick()
        assertEquals(1,followers);assertEquals(1,following)
        screenshot("profile-name-counts-inline.png")
    }
    @Test fun profileNameRemainsReadableWithAdminAndVerifiedBadges() {
        compose.setContent { MaterialTheme { Box(Modifier.width(240.dp)) {
            conectate.com.presentation.profile.ProfileNameAndCounts("Alvaro",4,1,{},{}) {
                Box(Modifier.width(80.dp)) { Text("ADMIN") }
                Box(Modifier.width(24.dp)) { Text("✓") }
            }
        } } }
        val name=compose.onNodeWithText("Alvaro").fetchSemanticsNode().boundsInRoot
        val first=compose.onNodeWithContentDescription("4 Seguidores").fetchSemanticsNode().boundsInRoot
        val badge=compose.onNodeWithText("ADMIN").fetchSemanticsNode().boundsInRoot
        val density=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(name.width>=56*density)
        assertTrue(name.right<=first.left)
        assertTrue(badge.top>=name.bottom)
        screenshot("profile-name-counts-with-badges.png")
    }
    @Test fun profileHeaderUsesSeparateRowsAtLargeAccessibilityFont() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
                Box(Modifier.width(240.dp)) { conectate.com.presentation.profile.ProfileNameAndCounts("Nombre con veinte caracteres",123456,678901,{},{}) }
            }
        } }
        val name=compose.onNodeWithText("Nombre con veinte caracteres").fetchSemanticsNode().boundsInRoot
        val first=compose.onNodeWithContentDescription("123456 Seguidores").fetchSemanticsNode().boundsInRoot
        val second=compose.onNodeWithContentDescription("678901 Siguiendo").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top>=name.bottom);assertTrue(first.right<=second.left||second.top>=first.bottom)
        compose.onNodeWithContentDescription("123456 Seguidores").assertIsDisplayed()
        compose.onNodeWithContentDescription("678901 Siguiendo").assertIsDisplayed()
        screenshot("profile-name-counts-large-font.png")
    }

}
