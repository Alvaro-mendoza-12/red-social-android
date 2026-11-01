package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMemberEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMemberEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.auth.AccountSwitchCoordinator
import conectate.com.data.auth.LocalAccountSwitchSession
import conectate.com.data.auth.PreparedLocalAccount
import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.localfallback.LocalFallbackUser
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class AccountOutboxVaultTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val cipher = object : AccountOutboxVault.TestCipher {
        override fun encrypt(value: String): String = value.reversed()
        override fun decrypt(value: String): String = value.reversed()
    }
    private val owners = mutableListOf<String>()

    @Before fun resetOwnerMarker() {
        context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun removeTestArchives() {
        for (owner in owners) {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
            java.io.File(context.noBackupFilesDir, "account_outbox/$hash.vault").delete()
        }
        context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun owner(): String = "account-${UUID.randomUUID()}".also(owners::add)

    private fun chat(id: String, owner: String) = ChatEntity(
        id = id, name = null, is_group = false, created_by = owner,
        participantsJson = "[\"$owner\",\"peer\"]", last_message_at = null,
        display_name = "Peer", display_image = null
    )

    private fun message(id: String, chatId: String, sender: String, pending: Boolean) = MessageEntity(
        id = id, chat_id = chatId, sender_id = sender, content = "Offline $id",
        content_type = "text", created_at = "2026-09-23T00:00:00Z",
        status = if (pending) "sending" else "sent", is_synced = !pending,
        pending_action = if (pending) "send" else null
    )

    @Test fun `pending direct group community and deletion work returns only to its owner`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val accountA = owner()
        val accountB = owner()
        val vault = AccountOutboxVault(context, db, cipher)
        try {
            db.chatDao().insertChat(chat("a-chat", accountA))
            db.messageDao().insertMessage(message("a-send", "a-chat", accountA, true))
            // A delete-for-me can target a message written by somebody else.
            db.messageDao().insertMessage(message("a-delete", "a-chat", "peer", false))
            db.messageDeletionDao().put(MessageDeletionEntity(
                message_id = "a-delete", actor_id = accountA,
                for_everyone = false, chat_id = "a-chat", revision = "revision-a",
                created_at = 1L
            ))
            db.groupDao().insertGroupsIgnore(listOf(GroupEntity(
                id = "a-group", name = "Group A", image = null, description = null,
                created_at = "2026-09-23", creator_id = accountA,
                last_message = null, last_message_at = null
            )))
            db.groupDao().insertMembers(listOf(GroupMemberEntity(
                group_id = "a-group", user_id = accountA, role = "member", joined_at = "2026-09-23"
            )))
            db.groupDao().insertMessages(listOf(GroupMessageEntity(
                id = "a-group-send", group_id = "a-group", sender_id = accountA,
                content = "Offline group", content_type = "text", created_at = "2026-09-23",
                status = "sending", is_synced = false, pending_action = "send"
            )))
            db.communityDao().insertCommunities(listOf(CommunityEntity(
                id = "a-community", name = "Community A", image = null,
                description = null, category = null, is_public = true,
                created_at = "2026-09-23", creator_id = accountA,
                last_message = null, last_message_at = null
            )))
            db.communityDao().insertMembers(listOf(CommunityMemberEntity(
                community_id = "a-community", user_id = accountA,
                role = "member", joined_at = "2026-09-23"
            )))
            db.communityDao().insertMessages(listOf(CommunityMessageEntity(
                id = "a-community-send", community_id = "a-community", sender_id = accountA,
                content = "Offline community", content_type = "text", created_at = "2026-09-23",
                status = "sending", is_synced = false, pending_action = "send"
            )))

            vault.preserveAndClear(accountA)
            assertNull(db.messageDao().getMessageById("a-send"))
            assertNull(db.groupDao().getMessageById("a-group-send"))

            db.chatDao().insertChat(chat("b-chat", accountB))
            db.messageDao().insertMessage(message("b-send", "b-chat", accountB, true))
            vault.preserveAndClear(accountB)

            vault.restore(accountA)
            assertNotNull(db.messageDao().getMessageById("a-send"))
            assertNotNull(db.groupDao().getMessageById("a-group-send"))
            assertNotNull(db.communityDao().getMessageById("a-community-send"))
            assertEquals("pending", db.messageDeletionDao().get("a-delete", accountA, false)?.state)
            assertNull(db.messageDao().getMessageById("b-send"))
            assertEquals(listOf("a-send"), db.messageDao().getPendingSendMessages().map { it.id })
            var wrongAccountBlocked = false
            try {
                vault.requireCompatibleAccount(accountB)
            } catch (_: IllegalStateException) {
                wrongAccountBlocked = true
            }
            assertTrue(wrongAccountBlocked)

            vault.preserveAndClear(accountA)
            vault.restore(accountB)
            assertNotNull(db.messageDao().getMessageById("b-send"))
            assertNull(db.messageDao().getMessageById("a-send"))
        } finally {
            db.close()
        }
    }

    @Test fun `failed archive never erases pending messages`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val account = owner()
        val failing = AccountOutboxVault(context, db, object : AccountOutboxVault.TestCipher {
            override fun encrypt(value: String): String = error("Key unavailable")
            override fun decrypt(value: String): String = error("Key unavailable")
        })
        try {
            db.chatDao().insertChat(chat("fail-chat", account))
            db.messageDao().insertMessage(message("fail-send", "fail-chat", account, true))
            var failed = false
            try {
                failing.preserveAndClear(account)
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue(failed)
            assertNotNull(db.messageDao().getMessageById("fail-send"))
            assertNull(context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)
                .getString("active_database_owner", null))
        } finally {
            db.close()
        }
    }

    @Test fun `stale owner marker on empty cache does not block another account`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val oldAccount = owner()
        val newAccount = owner()
        val oldHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(oldAccount.toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)
            .edit().putString("active_database_owner", oldHash).commit()
        try {
            AccountOutboxVault(context, db, cipher).requireCompatibleAccount(newAccount)
            assertNull(context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)
                .getString("active_database_owner", null))
        } finally {
            db.close()
        }
    }

    @Test fun `delayed outgoing cleanup never clears the new account idle cache`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val outgoing = owner()
        val incoming = owner()
        val vault = AccountOutboxVault(context, db, cipher)
        try {
            vault.restore(incoming)
            db.chatDao().insertChat(chat("incoming-chat", incoming))
            val lateCleanup = runCatching { vault.preserveAndClear(outgoing) }
            assertNotNull("An outgoing cleanup erased the new account cache", db.chatDao().getChatById("incoming-chat"))
            assertTrue(lateCleanup.isFailure)
            vault.requireCompatibleAccount(incoming)
        } finally { db.close() }
    }

    @Test fun `idle rows from previous account are cleared before another login`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val oldAccount = owner()
        val newAccount = owner()
        val oldHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(oldAccount.toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)
            .edit().putString("active_database_owner", oldHash).commit()
        try {
            db.chatDao().insertChat(chat("retained-chat", oldAccount))
            AccountOutboxVault(context, db, cipher).requireCompatibleAccount(newAccount)
            assertNull(db.chatDao().getChatById("retained-chat"))
            assertNull(context.getSharedPreferences("account_outbox_vault", Context.MODE_PRIVATE)
                .getString("active_database_owner", null))
        } finally {
            db.close()
        }
    }

    @Test fun `an absent owner marker cannot authorize clearing another account pending messages`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val outgoing = owner()
        val incoming = owner()
        try {
            db.chatDao().insertChat(chat("incoming-chat", incoming))
            db.messageDao().insertMessage(message("incoming-pending", "incoming-chat", incoming, true))
            val result = runCatching { AccountOutboxVault(context, db, cipher).preserveAndClear(outgoing) }
            assertTrue(result.isFailure)
            assertNotNull(db.messageDao().getMessageById("incoming-pending"))
        } finally { db.close() }
    }

    @Test fun `failed account completion restores original Room outbox and keeps target work archived`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val a = owner(); val b = owner()
        val vault = AccountOutboxVault(context, db, cipher)
        val guard = AuthSessionMutationGuard()
        val activation = Mutex()
        fun response(id: String) = LocalFallbackAuthResponse(LocalFallbackUser(id = id, email = "$id@example.test"),
            "$id-access", "$id-refresh")
        var current: String? = a
        var sessionResponse = response(a)
        try {
            vault.restore(a)
            db.chatDao().insertChat(chat("a-switch-chat", a))
            db.messageDao().insertMessage(message("a-switch-pending", "a-switch-chat", a, true))
            val session = LocalAccountSwitchSession(
                guard, activation, { current }, { current?.let { sessionResponse } }, {},
                { current = null }, {}, vault::preserveAndClear,
                { candidate, lease, _ -> activation.withLock {
                    vault.requireCompatibleAccount(candidate.user.id)
                    check(guard.advanceTransition(lease) { current = candidate.user.id; sessionResponse = candidate })
                } }, vault::restore, {},
            )
            val runner = AccountSwitchCoordinator<String>(
                open = { session }, prepare = {
                    PreparedLocalAccount(StoredAccountSession(user_id = b, email = "$b@example.test",
                        access_token = "$b-access", refresh_token = "$b-refresh"), response(b))
                }, complete = { _, _ ->
                    db.chatDao().insertChat(chat("b-switch-chat", b))
                    db.messageDao().insertMessage(message("b-switch-pending", "b-switch-chat", b, true))
                    error("profile-open-failed")
                }, onRollbackFailure = { throw AssertionError("Local rollback must succeed", it) },
            )
            val result = runner.switch(b)
            assertEquals("profile-open-failed", result.exceptionOrNull()?.message)
            assertEquals(a, current)
            assertNotNull(db.messageDao().getMessageById("a-switch-pending"))
            assertNull(db.messageDao().getMessageById("b-switch-pending"))
            vault.preserveAndClear(a)
            vault.restore(b)
            assertNotNull(db.messageDao().getMessageById("b-switch-pending"))
            assertNull(db.messageDao().getMessageById("a-switch-pending"))
        } finally { db.close() }
    }
}
