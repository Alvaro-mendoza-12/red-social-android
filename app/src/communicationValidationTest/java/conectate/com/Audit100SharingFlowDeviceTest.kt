package conectate.com

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.testing.SharingAuditEntryPoint
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Full UI -> real API -> QA recipient -> original. All accounts/content belong to this audit. */
class Audit100SharingFlowDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val progress = object : org.junit.rules.TestWatcher() {
        override fun failed(error: Throwable, description: org.junit.runner.Description) { android.util.Log.e("Sharing100", "Failed: ${description.methodName}", error) }
    }
    private fun activity(): MainActivity = listOf(
        androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.STARTED,
        androidx.test.runner.lifecycle.Stage.PAUSED,
    ).flatMap { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
        .filterIsInstance<MainActivity>().first { !it.isDestroyed }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val component get() = EntryPointAccessors.fromApplication(context, SharingAuditEntryPoint::class.java)
    private lateinit var fixture: JSONObject
    private lateinit var active: JSONObject
    private lateinit var token: String
    private val postId get() = fixture.getString("postId")
    private val link get() = "https://red-social-android.vercel.app/post/$postId"
    private val content get() = fixture.getString("content")
    private fun user(label: String): JSONObject = fixture.getJSONArray("users").let { users ->
        (0 until users.length()).map { users.getJSONObject(it) }.single { it.getString("label") == label }
    }
    private val auditClient = okhttp3.OkHttpClient.Builder()
        .dns(conectate.com.core.network.PublicBackendDns)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private fun api(path: String, method: String = "GET", body: JSONObject? = null, access: String? = token): JSONObject {
        val base = RuntimeBackendEndpoint.baseUrl.trimEnd('/')
        check(base.startsWith("https://")) { "An authenticated HTTPS QA endpoint is required" }
        val request = okhttp3.Request.Builder().url(base + path)
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        access?.let { request.header("Authorization", "Bearer $it") }
        return auditClient.newCall(request.build()).execute().use { response ->
            assertTrue("QA API rejected $method ${path.substringBefore('?')} (${response.code})", response.isSuccessful)
            JSONObject(response.body!!.string())
        }
    }
    private fun login(label: String) {
        android.util.Log.i("Sharing100", "Login start: $label")
        active = user(label)
        runBlocking(Dispatchers.IO) {
            if (component.authService().getCurrentUserId() != null) component.authService().signOut()
            component.authRepository().signInWithEmail(active.getString("email"), fixture.getString("password")).getOrThrow()
        }
        token = checkNotNull(component.authService().getAccessToken())
        android.util.Log.i("Sharing100", "Repository login confirmed: $label")
        compose.waitUntil(30_000) { component.authService().getCurrentUserId() == active.getString("id") }
    }
    @Before fun prepareOnlyOwnedFixtures() {
        val supplied = checkNotNull(InstrumentationRegistry.getArguments().getString("sharingFixture")) { "QA fixture input is required" }
        val file = File(supplied)
        check(file.canonicalPath.startsWith(context.getExternalFilesDir(null)!!.canonicalPath + File.separator))
        fixture = JSONObject(file.readText())
        android.util.Log.i("Sharing100", "Owned fixture loaded")
        check(fixture.getString("content").startsWith("Publicación QA compartir "))
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        compose.waitUntil(30_000) { !component.authService().isInitializing.value }
        compose.waitUntil(30_000) {
            listOf("Tu mundo empieza aquí", "Qué bueno verte", "Mensajes").any { text ->
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        login("A")
        api("/v1/data/feed/posts/$postId/share", "DELETE")
    }
    @After fun revokeThisTestsSession() {
        if (::active.isInitialized) runBlocking(Dispatchers.IO) { component.authService().signOut() }
        instrumentation.runOnMainSync {
            runCatching { activity().finish() }
        }
    }
    private fun open(uri: String) {
        instrumentation.runOnMainSync {
            activity().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, MainActivity::class.java))
        }
    }
    private fun awaitText(value: String) {
        try {
            compose.waitUntil(45_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            screenshot("sharing100-failed-wait-${System.currentTimeMillis()}.png")
            throw error
        }
    }
    private fun openPost() { open("conectate://post/$postId"); awaitText(content) }
    private fun awaitDisplayed(value: String) {
        try {
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText(value).assertIsDisplayed(); true }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            screenshot("sharing100-failed-displayed-${System.currentTimeMillis()}.png")
            throw error
        }
    }
    private fun shareMenu() {
        compose.onAllNodes(hasText("Compartir") and hasClickAction()).onLast().performScrollTo().performClick()
        awaitDisplayed("Copiar enlace")
    }
    private fun screenshot(name: String) {
        val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun openConversation(kind: String, id: String) {
        android.util.Log.i("Sharing100", "Open recipient conversation: $kind")
        instrumentation.runOnMainSync {
            val intent = Intent(context, MainActivity::class.java).putExtra("type", "new_message")
                .putExtra("target_type", kind).putExtra("chat_id", id)
            if (kind == "group") intent.putExtra("group_id", id)
            if (kind == "community") intent.putExtra("community_id", id)
            activity().startActivity(TrustedNavigationIntents.markTrusted(context, intent))
        }
        awaitText(link)
    }
    private fun clickReceivedLink() {
        // A collection bubble merges the author and body. Target the actual text leaf,
        // otherwise the author's TextLayoutResult produces a tap on their profile.
        val node = compose.onAllNodes(hasText(link) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).onLast()
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single { it.layoutInput.text.text.contains(link) }
        val offset = layout.layoutInput.text.text.indexOf(link) + 5
        val box = layout.getBoundingBox(offset)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }
        awaitText(content)
        compose.onNodeWithText("Publicación").assertIsDisplayed()
    }
    @Test fun socialShareActuallyArrivesInChatGroupAndCommunityAndEveryLinkOpens() {
        open("conectate://social"); awaitText("Seguidos")
        compose.onNodeWithText("Seguidos").performClick(); awaitText(content)
        shareMenu(); compose.onNodeWithText("Enviar a amigo").performClick()
        awaitText("Compartir con…")
        for (name in listOf(user("B").getString("displayName"), fixture.getString("groupName"), fixture.getString("communityName"))) {
            compose.onNode(hasSetTextAction() and hasText("Buscar en todo")).performTextReplacement(name)
            val row = hasText(name) and hasAnyDescendant(isToggleable())
            compose.waitUntil(15_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(row).performScrollTo().performClick()
            compose.onNode(row).onChildren().filter(isToggleable()).onFirst().assertIsOn()
        }
        instrumentation.runOnMainSync {
            val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(activity().window.decorView.windowToken, 0)
        }
        compose.onAllNodes(hasText("Compartir") and hasClickAction()).onLast().performScrollTo().performClick()
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText("Compartir con…").fetchSemanticsNodes().isEmpty() ||
                compose.onAllNodesWithText("Se compartió en", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Compartir con…").fetchSemanticsNodes().isNotEmpty()) {
            screenshot("sharing100-unconfirmed-destination.png")
            fail("The share selector must confirm every recipient; inspect the captured status and send stages")
        }
        screenshot("sharing100-social-send-completed.png")
        login("B")
        for ((kind, id) in listOf("chat" to fixture.getString("chatId"), "group" to fixture.getString("groupId"), "community" to fixture.getString("communityId"))) {
            openConversation(kind, id)
            screenshot("sharing100-received-$kind.png")
            clickReceivedLink()
        }
    }
    @Test fun profileShareKeepsTheOriginalAndCanBeViewedAndRemovedAcrossAccounts() {
        openPost(); shareMenu(); compose.onNodeWithText("Añadir a mi perfil").performClick()
        awaitText("Compartido")
        val a = user("A").getString("id")
        assertTrue(api("/v1/data/feed?sharedBy=$a").getJSONArray("items").let { rows ->
            (0 until rows.length()).any { rows.getJSONObject(it).getString("id") == postId }
        })
        login("B"); open("conectate://profile/$a"); awaitText("Compartidos")
        compose.onNodeWithText("Compartidos").performScrollTo().performClick(); awaitText(content)
        compose.onNodeWithText(content).performScrollTo().assertIsDisplayed()
        screenshot("sharing100-profile-original-visible-to-recipient.png")
        shareMenu(); awaitDisplayed("Añadir a mi perfil"); compose.onNodeWithText("Añadir a mi perfil").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Copiar enlace").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(user("B").getString("displayName")).performScrollTo().performClick()
        awaitText("@${user("B").getString("username")}")
        login("A"); openPost(); awaitText("Compartido")
        compose.onAllNodes(hasText("Compartido") and hasClickAction()).onLast().performClick()
        compose.onNodeWithText("Quitar de mi perfil").performClick()
        awaitText("Compartir")
        assertFalse(api("/v1/data/feed?sharedBy=$a").getJSONArray("items").let { rows ->
            (0 until rows.length()).any { rows.getJSONObject(it).getString("id") == postId }
        })
    }
    @Test fun copiedLinkReturnsToTheExactPublication() {
        openPost(); shareMenu(); compose.onNodeWithText("Copiar enlace").performClick()
        instrumentation.runOnMainSync {
            assertEquals(link, (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text.toString())
        }
        open(link); awaitText(content)
        screenshot("sharing100-copied-link-opens-original.png")
    }
    @Test fun externalSharesheetDeliversOneUsableLinkToAnotherApplication() {
        val captured = AtomicReference<String?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { captured.set(intent?.getStringExtra("shared_text")) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("novvin.sharing100.CAPTURED"), ContextCompat.RECEIVER_EXPORTED)
        try {
            openPost(); shareMenu(); compose.onNodeWithText("Compartir enlace externo").performClick()
            val deadline = System.currentTimeMillis() + 20_000
            var clicked = false
            while (!clicked && System.currentTimeMillis() < deadline) {
                val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Receptor QA compartir").orEmpty()
                for (node in nodes) {
                    var candidate: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (!clicked && candidate != null && depth++ < 5) {
                        if (candidate!!.isClickable) clicked = candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        candidate = candidate!!.parent
                    }
                    if (clicked) break
                }
                if (!clicked) Thread.sleep(150)
            }
            assertTrue("Sharesheet did not expose the separate QA receiver", clicked)
            compose.waitUntil(15_000) { captured.get() != null }
            val text = captured.get()!!
            assertTrue(text.contains(link)); assertFalse(text.contains("https://conectate.com/"))
            assertEquals(1, Regex("https://\\S+").findAll(text).count())
            open(link); awaitText(content)
            screenshot("sharing100-external-receiver-return.png")
        } finally { context.unregisterReceiver(receiver) }
    }
    @Test fun signingInAfterOpeningAnExternalLinkReturnsToTheOriginal() {
        runBlocking(Dispatchers.IO) { component.authService().signOut() }
        open(link); awaitText("Tu mundo empieza aquí")
        val manual = if (compose.onAllNodesWithText("Iniciar sesión con otra cuenta").fetchSemanticsNodes().isNotEmpty())
            "Iniciar sesión con otra cuenta" else "Iniciar sesión"
        compose.onNodeWithText(manual).performScrollTo().performClick()
        awaitText("Qué bueno verte")
        compose.onNodeWithText("Correo de tu cuenta").performTextInput(user("A").getString("email"))
        compose.onNodeWithText("Tu contraseña").performTextInput(fixture.getString("password"))
        compose.onNode(hasText("Iniciar Sesión") and hasClickAction()).performClick()
        awaitText(content); compose.onNodeWithText("Publicación").assertIsDisplayed()
        screenshot("sharing100-login-returns-to-original.png")
    }
}
