package conectate.com.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import conectate.com.data.model.CallHistoryEntry
import org.junit.Assert.*
import org.junit.Test

class CallHistoryAccountStorageTest {
    private val entry = CallHistoryEntry(callId = "call-a", title = "Llamada", subtitle = "Prueba",
        contextType = "dm", statusLabel = "Finalizada")

    @Test fun accountsCannotSeeOrClearEachOthersHistory() {
        val prefs = mutablePreferencesOf()
        CallHistoryAccountStorage.write(prefs, "account-a", listOf(entry))
        CallHistoryAccountStorage.write(prefs, "account-b", listOf(entry.copy(callId = "call-b")))
        assertEquals("call-a", CallHistoryAccountStorage.read(prefs, "account-a").single().callId)
        assertEquals("call-b", CallHistoryAccountStorage.read(prefs, "account-b").single().callId)
        CallHistoryAccountStorage.write(prefs, "account-a", emptyList())
        assertTrue(CallHistoryAccountStorage.read(prefs, "account-a").isEmpty())
        assertEquals(1, CallHistoryAccountStorage.read(prefs, "account-b").size)
    }

    @Test fun aSignedOutSessionCannotReadOrWriteHistory() {
        val prefs = mutablePreferencesOf()
        CallHistoryAccountStorage.write(prefs, "", listOf(entry))
        CallHistoryAccountStorage.write(prefs, null, listOf(entry))
        assertTrue(prefs.asMap().isEmpty())
        assertTrue(CallHistoryAccountStorage.read(prefs, null).isEmpty())
    }

    @Test fun legacyUnownedHistoryIsPreservedButNeverAssignedToAnotherAccount() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("entries_json") to "legacy archive")
        assertTrue(CallHistoryAccountStorage.read(prefs, "account-a").isEmpty())
        CallHistoryAccountStorage.write(prefs, "account-a", listOf(entry))
        assertEquals("legacy archive", prefs[stringPreferencesKey("entries_json")])
        assertEquals(entry, CallHistoryAccountStorage.read(prefs, " ACCOUNT-A ").single())
    }
}
