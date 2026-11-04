package conectate.com.presentation.messages.agreements

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.local.AgreementRecoveryPreferences
import conectate.com.data.localfallback.AccountDeletedException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AgreementRecoveryJournalTest {
    private lateinit var preferences: SharedPreferences
    private val chat = "33333333-3333-4333-8333-333333333333"
    private val other = "44444444-4444-4444-8444-444444444444"
    private val source = "55555555-5555-4555-8555-555555555555"
    private val id = "66666666-6666-4666-8666-666666666666"
    private val operationId = "77777777-7777-4777-8777-777777777777"
    private fun journal() = AgreementRecoveryJournal(preferences)
    @Before fun before() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("agreement-journal-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }
    @Test fun aNewInstanceRestoresTheDraftAndExactPendingCommandOnlyInItsAccountAndConversation() {
        val draft = AgreementDraft(source, "El sábado")
        val pending = AgreementPendingWrite(id, AgreementCommand(operationId, id = id, sourceMessageId = source, text = draft.text))
        val saved = journal().replace(" ALICE ", chat, null, draft, pending)
        assertEquals(saved, journal().read("alice", chat))
        assertNull(journal().read("bob", chat)); assertNull(journal().read("alice", other))
        assertFalse(preferences.all.keys.single().contains("alice")); assertFalse(preferences.all.keys.single().contains(chat))
    }
    @Test fun aLateScreenCannotOverwriteAnotherScreensDraftOrResurrectADiscardedDraft() {
        val original = journal().replace("alice", chat, null, AgreementDraft(source, "Primero"), null)
        val discarded = journal().replace("alice", chat, original, null, null)
        assertThrows(AgreementRecoveryConflict::class.java) { journal().replace("alice", chat, original, AgreementDraft(source, "Tarde"), null) }
        assertThrows(AgreementRecoveryConflict::class.java) { journal().replace("alice", chat, null, AgreementDraft(source, "Tarde"), null) }
        assertEquals(discarded, journal().read("alice", chat))
    }
    @Test fun aFailedCommitRestoresThePreviousSnapshotAndDoesNotAcknowledgeANewOperation() {
        val first = journal().replace("alice", chat, null, AgreementDraft(source, "Guardado"), null)
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        assertThrows(IOException::class.java) { AgreementRecoveryJournal(failing).replace("alice", chat, first, AgreementDraft(source, "No confirmado"), null) }
        assertEquals(first, journal().read("alice", chat))
    }
    @Test fun corruptOrFutureDataIsPreservedUntilAnExplicitAccountCleanup() {
        journal().replace("alice", chat, null, AgreementDraft(source), null)
        val key = preferences.all.keys.single()
        for (raw in listOf("{corrupt", "{\"schema\":2}", "x".repeat(16_001))) {
            preferences.edit().putString(key, raw).commit()
            assertThrows(Exception::class.java) { journal().read("alice", chat) }
            assertThrows(Exception::class.java) { journal().replace("alice", chat, null, null, null) }
            assertEquals(raw, preferences.getString(key, null))
        }
        journal().eraseAccount("alice"); assertNull(journal().read("alice", chat))
    }
    @Test fun deletingAnAccountRemovesOnlyItsDraftsAndPendingCommandsInEveryConversation() {
        journal().replace("alice", chat, null, AgreementDraft(source, "A"), null)
        journal().replace("alice", other, null, AgreementDraft(source, "A2"), null)
        val bob = journal().replace("bob", chat, null, AgreementDraft(source, "B"), null)
        journal().eraseAccount(" ALICE ")
        assertNull(journal().read("alice", chat)); assertNull(journal().read("alice", other))
        assertEquals(bob, journal().read("bob", chat))
    }
    @Test fun aStoredCommandMustMatchTheDraftItWillSendAndCannotForgeAnActionPayload() {
        val draft = AgreementDraft(source, "Texto A")
        val changed = AgreementPendingWrite(id, AgreementCommand(operationId, id = id, sourceMessageId = source, text = "Texto B"))
        assertThrows(IllegalArgumentException::class.java) { journal().replace("alice", chat, null, draft, changed) }
        val forged = AgreementPendingWrite(id, AgreementCommand(operationId, id = id, expectedVersion = 1, action = "accept", text = "Sobra"))
        assertThrows(IllegalArgumentException::class.java) { journal().replace("alice", chat, null, null, forged) }
        assertNull(journal().read("alice", chat))
    }
    @Test fun anAccountDeletionRemovesItsMaterialAndPermanentlyRejectsLateWrites() {
        val first = journal().replace("alice", chat, null, AgreementDraft(source, "Eliminar"), null)
        val bob = journal().replace("bob", chat, null, AgreementDraft(source, "Conservar"), null)
        AgreementRecoveryPreferences.purgeDeletedAccount(preferences, " ALICE ")
        assertThrows(AccountDeletedException::class.java) { journal().read("alice", chat) }
        assertThrows(AccountDeletedException::class.java) { journal().replace("alice", chat, first, AgreementDraft(source, "Tarde"), null) }
        assertThrows(AccountDeletedException::class.java) { journal().replace("alice", chat, null, AgreementDraft(source, "Reabrir"), null) }
        assertEquals(bob, journal().read("bob", chat))
        assertFalse(preferences.all.values.any { it.toString().contains("Eliminar") })
    }
}
