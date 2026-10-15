package conectate.com

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.repository.LocalInboxRepository
import conectate.com.data.repository.forAccount
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual launcher and local inbox; only this task's independently installed QA identities. */
@RunWith(AndroidJUnit4::class)
class LocalInboxStartupDeviceAuditTest {
    @Test(timeout = 60_000) fun restoredAccountOpensTheActualInboxWithoutRequiringOtherGraphs() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditLocalInboxStartup") == "true")
        val context = instrumentation.targetContext
        assertEquals("conectate.com.qa.cierre11", context.packageName)
        val fixtures = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        val ownedIds = (0 until fixtures.length()).map { fixtures.getJSONObject(it).getString("id").lowercase() }.toSet()
        assertEquals(2, ownedIds.size)
        val entry = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java)
        val auth = entry.authService()
        withTimeout(15_000) { while (auth.isInitializing.value) delay(100) }
        val owner = checkNotNull(auth.getCurrentUserId()).trim().lowercase()
        assertTrue("Only disposable QA sessions may be exercised", owner in ownedIds)
        val field = entry.javaClass.getDeclaredField("localInboxRepositoryProvider").apply { isAccessible = true }
        val repository = (field.get(entry) as Provider<*>).get() as LocalInboxRepository
        val snapshot = withTimeout(15_000) { repository.snapshots.first { it.accountId == owner } }
        assertSame(snapshot, snapshot.forAccount(owner))
        assertTrue(snapshot.forAccount(ownedIds.first { it != owner }).conversations.isEmpty())
        assertNull(snapshot.forAccount(null).accountId)
        assertTrue(snapshot.conversations.all { item -> item.chat.participants.any { it.trim().lowercase() == owner } })
        // Exercise the launcher through Android, independently of ActivityScenario's
        // retained singleTask teardown failures. The host stops only our QA package afterwards.
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            withTimeout(20_000) {
                while (true) {
                    val labels = mutableSetOf<String>()
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (node == null) return
                        node.text?.toString()?.let(labels::add)
                        node.contentDescription?.toString()?.let(labels::add)
                        repeat(node.childCount) { index -> collect(node.getChild(index)) }
                    }
                    collect(instrumentation.uiAutomation.rootInActiveWindow)
                    if (setOf("Mensajes", "Grupos", "Comunidades", "Voz", "Buscar").all { it in labels }) {
                        assertFalse("The actual inbox must be usable without an initial error", "Reintentar" in labels)
                        break
                    }
                    delay(100)
                }
            }
            assertEquals(owner, auth.getCurrentUserId()?.trim()?.lowercase())
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "local-inbox-launcher.json")
                .writeText(JSONObject().put("actualLauncherAndInboxVisible", true)
                    .put("localSnapshotLoaded", true).put("ownRowCount", snapshot.conversations.size)
                    .put("cachedSnapshotRejectedForOtherAccountAndLogout", true)
                    .put("ownAccountPreserved", true).put("optimizedLatencyClaim", false).toString(2))
        } finally {
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }
}
