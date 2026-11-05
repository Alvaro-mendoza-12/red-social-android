package conectate.com.presentation.purpose

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.local.AgreementRecoveryPreferences
import conectate.com.data.localfallback.AccountDeletedException
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class,sdk = [34])
class PurposeRecoveryStoreTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val target = PurposeTarget("social","33333333-3333-4333-8333-333333333333")
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences("purpose-qa-"+java.util.UUID.randomUUID(),Context.MODE_PRIVATE)
    @Test fun recoveryIsScopedByAccountAndAStaleScreenCannotOverwriteANewerRecord() = runBlocking<Unit> {
        val preferences = preferences(); var owner = a
        val guard = AuthSessionMutationGuard(); val lease = guard.captureAccountLease { owner }
        val store = PurposeRecoveryStore({preferences},guard::commitForLease)
        val draft = buildJsonObject { put("conclusion","Una conclusión privada") }
        val saved = store.replace(lease,target,PurposeRecovery(),PurposeRecovery(draft = draft))
        assertEquals(draft,store.read(lease,target).draft)
        assertThrows(PurposeRecoveryConflict::class.java) { runBlocking { store.replace(lease,target,PurposeRecovery(),PurposeRecovery()) } }
        assertEquals(saved,store.read(lease,target))
        owner = b
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { store.read(lease,target) } }
        val other = guard.captureAccountLease { owner }
        assertEquals(PurposeRecovery(),store.read(other,target))
        assertFalse(preferences.all.keys.any { it.contains(a) || it.contains(checkNotNull(target.id)) })
    }
    @Test fun accountDeletionPurgesTheNewNamespaceAndItsTombstonePreventsResurrection() = runBlocking<Unit> {
        val preferences = preferences(); val guard = AuthSessionMutationGuard(); val lease = guard.captureAccountLease { a }
        val store = PurposeRecoveryStore({preferences},guard::commitForLease)
        store.replace(lease,target,PurposeRecovery(),PurposeRecovery(draft = buildJsonObject { put("conclusion","Eliminar") }))
        AgreementRecoveryPreferences.purgeDeletedAccount(preferences,a)
        assertFalse(preferences.all.keys.any { it.startsWith(AgreementRecoveryPreferences.accountPrefix(a)) })
        assertThrows(AccountDeletedException::class.java) { runBlocking { store.read(lease,target) } }
        assertThrows(AccountDeletedException::class.java) { runBlocking { store.replace(lease,target,PurposeRecovery(),PurposeRecovery()) } }
    }
    @Test fun aFailedDiskCommitDoesNotReplaceThePreviouslyAcknowledgedDraft() = runBlocking<Unit> {
        val preferences = preferences(); val guard = AuthSessionMutationGuard(); val lease = guard.captureAccountLease { a }
        val real = PurposeRecoveryStore({preferences},guard::commitForLease)
        val old = real.replace(lease,target,PurposeRecovery(),PurposeRecovery(draft = buildJsonObject { put("conclusion","Anterior") }))
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?,value: String?): SharedPreferences.Editor { editor.putString(key,value);return this }
                    override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key);return this }
                    override fun commit() = false
                }
            }
        }
        val store = PurposeRecoveryStore({failing},guard::commitForLease)
        assertThrows(java.io.IOException::class.java) { runBlocking { store.replace(lease,target,old,PurposeRecovery()) } }
        assertEquals(old,real.read(lease,target))
    }
}
