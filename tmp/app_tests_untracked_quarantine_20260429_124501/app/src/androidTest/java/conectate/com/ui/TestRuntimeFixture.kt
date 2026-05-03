package conectate.com.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.core.testing.RuntimeSessionOverride
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.data.model.StoredAccountSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SeededSavedAccountsHarness(
    val activeSession: RuntimeSessionOverride,
    val activeAccount: StoredAccountSession,
    val secondaryAccount: StoredAccountSession
)

data class SeededMultiAccountMessagesHarness(
    val activeSession: RuntimeSessionOverride,
    val activeAccount: StoredAccountSession,
    val secondaryAccount: StoredAccountSession,
    val pendingDestination: MultiAccountPendingDestination
)

object TestRuntimeFixture {
    private const val MULTI_ACCOUNT_INBOX_PREFS = "multi_account_inbox"
    private const val KEY_CACHED_DIRECT_PREFIX = "cached_direct_unread_"
    private const val KEY_PENDING_CONVERSATION_PREFIX = "pending_conversation_"
    private const val KEY_LATEST_DESTINATION_PREFIX = "latest_destination_"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun seedSavedAccountHarness(
        userId: String = "qa-user-001",
        email: String = "qa.saved.account@conectate.test",
        displayName: String = "Cuenta QA"
    ): RuntimeSessionOverride {
        val context = appContext()
        val session = RuntimeSessionOverride(
            userId = userId,
            email = email,
            accessToken = "debug-access-token-$userId",
            refreshToken = "debug-refresh-token-$userId",
            displayName = displayName,
            avatarUrl = null
        )
        val storedAccount = StoredAccountSession(
            user_id = userId,
            email = email,
            access_token = session.accessToken,
            refresh_token = session.refreshToken,
            display_name = displayName,
            avatar_url = null
        )

        val prefs = context.getSharedPreferences(TestRuntimeOverrides.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(TestRuntimeOverrides.ENABLE_AUTH_FIXTURES_KEY, true)
            .apply()

        TestRuntimeOverrides.setStoredAccounts(context, listOf(storedAccount))
        TestRuntimeOverrides.setCurrentSession(context, session)
        return session
    }

    fun seedSavedAccountLoginHarness(
        userId: String = "qa-user-001",
        email: String = "qa.saved.account@conectate.test",
        displayName: String = "Cuenta QA"
    ): RuntimeSessionOverride {
        val session = seedSavedAccountHarness(
            userId = userId,
            email = email,
            displayName = displayName
        )
        TestRuntimeOverrides.clearSession(appContext())
        return session
    }

    fun seedDualSavedAccountsHarness(): SeededSavedAccountsHarness {
        val context = appContext()
        val activeSession = RuntimeSessionOverride(
            userId = "qa-user-a",
            email = "alvaro@conectate.test",
            accessToken = "debug-access-token-qa-user-a",
            refreshToken = "debug-refresh-token-qa-user-a",
            displayName = "Alvaro QA"
        )
        val activeAccount = StoredAccountSession(
            id = "saved-account-a",
            user_id = activeSession.userId,
            email = activeSession.email,
            access_token = activeSession.accessToken,
            refresh_token = activeSession.refreshToken,
            display_name = activeSession.displayName,
            avatar_url = null,
            last_used_at = System.currentTimeMillis()
        )
        val secondaryAccount = StoredAccountSession(
            id = "saved-account-b",
            user_id = "qa-user-b",
            email = "conectate@conectate.test",
            access_token = "debug-access-token-qa-user-b",
            refresh_token = "debug-refresh-token-qa-user-b",
            display_name = "Conectate QA",
            avatar_url = null,
            last_used_at = System.currentTimeMillis() - 10_000
        )

        enableAuthFixtures(context)
        TestRuntimeOverrides.setStoredAccounts(context, listOf(activeAccount, secondaryAccount))
        TestRuntimeOverrides.setCurrentSession(context, activeSession)

        return SeededSavedAccountsHarness(
            activeSession = activeSession,
            activeAccount = activeAccount,
            secondaryAccount = secondaryAccount
        )
    }

    fun seedDualSavedAccountsLoginHarness(): SeededSavedAccountsHarness {
        val harness = seedDualSavedAccountsHarness()
        TestRuntimeOverrides.clearSession(appContext())
        return harness
    }

    fun seedMultiAccountMessagesHarness(): SeededMultiAccountMessagesHarness {
        val harness = seedDualSavedAccountsHarness()
        val context = appContext()
        val pendingDestination = MultiAccountPendingDestination(
            targetType = "chat",
            conversationId = "chat-secondary-001",
            userId = "contact-secondary-001",
            conversationTitle = "Cabrise",
            senderName = "Cabrise",
            preview = "Te escribio a la cuenta B",
            focusMessageId = "message-secondary-001",
            receivedAt = System.currentTimeMillis()
        )

        context.getSharedPreferences(MULTI_ACCOUNT_INBOX_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                KEY_CACHED_DIRECT_PREFIX + harness.secondaryAccount.id,
                1
            )
            .putStringSet(
                KEY_PENDING_CONVERSATION_PREFIX + harness.secondaryAccount.id,
                setOf(pendingDestination.conversationKey)
            )
            .putString(
                KEY_LATEST_DESTINATION_PREFIX + harness.secondaryAccount.id,
                json.encodeToString(pendingDestination)
            )
            .apply()

        return SeededMultiAccountMessagesHarness(
            activeSession = harness.activeSession,
            activeAccount = harness.activeAccount,
            secondaryAccount = harness.secondaryAccount,
            pendingDestination = pendingDestination
        )
    }

    fun clear() {
        val context = appContext()
        context.getSharedPreferences(TestRuntimeOverrides.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(TestRuntimeOverrides.ENABLE_AUTH_FIXTURES_KEY)
            .remove(TestRuntimeOverrides.SESSION_OVERRIDE_KEY)
            .remove(TestRuntimeOverrides.STORED_ACCOUNTS_OVERRIDE_KEY)
            .apply()
        context.deleteSharedPreferences(MULTI_ACCOUNT_INBOX_PREFS)
    }

    private fun enableAuthFixtures(context: Context) {
        context.getSharedPreferences(TestRuntimeOverrides.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(TestRuntimeOverrides.ENABLE_AUTH_FIXTURES_KEY, true)
            .apply()
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()
}
