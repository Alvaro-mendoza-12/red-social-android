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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.testing.SharingAuditEntryPoint
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Full UI -> real API -> QA recipient -> original. All accounts/content belong to this audit. */
class Audit100SharingFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
    private fun api(path: String, method: String = "GET", body: JSONObject? = null, access: String? = token): JSONObject {
        val base = RuntimeBackendEndpoint.baseUrl.trimEnd('/')
        check(base.startsWith("https://")) { "An authenticated HTTPS QA endpoint is required" }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/json")
        access?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        try {
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            assertTrue("QA API rejected $method ${path.substringBefore('?')} (${connection.responseCode})", connection.responseCode in 200..299)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun login(label: String) {
        active = user(label)
        val session = api("/v1/auth/login", "POST", JSONObject().put("email", active.getString("email"))
            .put("password", fixture.getString("password")).put("client", "android"), null)
        token = session.getString("accessToken")
        runBlocking(Dispatchers.IO) {
            component.authService().importLocalFallbackStoredSession(token, session.getString("refreshToken"),
                active.getString("email"), active.getString("id"), active.getString("displayName"), null).getOrThrow()
        }
        compose.waitUntil(30_000) { component.authService().getCurrentUserId() == active.getString("id") }
    }
    @Before fun prepareOnlyOwnedFixtures() {
        val supplied = checkNotNull(InstrumentationRegistry.getArguments().getString("sharingFixture")) { "QA fixture input is required" }
        val file = File(supplied)
        check(file.canonicalPath.startsWith(context.getExternalFilesDir(null)!!.canonicalPath + File.separator))
        fixture = JSONObject(file.readText())
        check(fixture.getString("content").startsWith("Publicación QA compartir "))
        login("A")
        api("/v1/data/feed/posts/$postId/share", "DELETE")
    }
    @After fun revokeThisTestsSession() {
        if (::active.isInitialized) runBlocking(Dispatchers.IO) { component.authService().signOut() }
    }
    private fun open(uri: String) {
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, MainActivity::class.java))
        }
    }
    private fun awaitText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openPost() { open("conectate://post/$postId"); awaitText(content) }
    private fun shareMenu() { compose.onAllNodes(hasText("Compartir") and hasClickAction()).onLast().performClick() }
    private fun screenshot(name: String) {
        val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun openConversation(kind: String, id: String) {
        compose.runOnUiThread {
            val intent = Intent(context, MainActivity::class.java).putExtra("type", "new_message")
                .putExtra("target_type", kind).putExtra("chat_id", id)
            if (kind == "group") intent.putExtra("group_id", id)
            if (kind == "community") intent.putExtra("community_id", id)
            compose.activity.startActivity(TrustedNavigationIntents.markTrusted(context, intent))
        }
        awaitText(link)
    }
    private fun clickReceivedLink() {
        val node = compose.onNodeWithText(link)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.first()
        val offset = layout.layoutInput.text.text.indexOf("https://").coerceAtLeast(0) + 5
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
            compose.onNodeWithText(name).performScrollTo().performClick()
        }
        compose.onAllNodes(hasText("Compartir") and hasClickAction()).onLast().performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithText("Compartir con…").fetchSemanticsNodes().isEmpty() }
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
        shareMenu(); compose.onNodeWithText("Añadir a mi perfil").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(user("B").getString("displayName")).performClick()
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
        compose.runOnUiThread {
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
                    repeat(5) {
                        if (candidate?.isClickable == true) { clicked = candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); return@repeat }
                        candidate = candidate?.parent
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
        open(link); awaitText("Qué bueno verte")
        compose.onNodeWithText("Correo de tu cuenta").performTextInput(user("A").getString("email"))
        compose.onNodeWithText("Tu contraseña").performTextInput(fixture.getString("password"))
        compose.onNode(hasText("Iniciar Sesión") and hasClickAction()).performClick()
        awaitText(content); compose.onNodeWithText("Publicación").assertIsDisplayed()
        screenshot("sharing100-login-returns-to-original.png")
    }
}
