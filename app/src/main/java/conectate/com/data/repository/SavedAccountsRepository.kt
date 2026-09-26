package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.security.EncryptionManager
import conectate.com.data.model.SavedAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.savedAccountsDataStore by preferencesDataStore(name = "saved_accounts")

@Singleton
class SavedAccountsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager
) {
    private val scope = newSafeScope("SavedAccountsRepository", Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    private val accountsKey = stringPreferencesKey("saved_accounts_json")
    private val serializer = ListSerializer(SavedAccount.serializer())
    
    companion object {
        private const val MAX_SAVED_ACCOUNTS = 5
        private const val PREFS_ACTIVE_ACCOUNT = "active_account_id"
        private const val PREFS_NAME = "account_prefs"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _accountsFlow = MutableStateFlow<List<SavedAccount>>(emptyList())
    val accountsFlow: Flow<List<SavedAccount>> = _accountsFlow.asStateFlow()
    
    private val _activeAccountId = MutableStateFlow<String?>(null)
    val activeAccountId: Flow<String?> = _activeAccountId.asStateFlow()
    
    init {
        scope.launch {
            loadAccounts()
            _activeAccountId.value = prefs.getString(PREFS_ACTIVE_ACCOUNT, null)
        }
    }
    
    private suspend fun loadAccounts() {
        val accounts = withContext(Dispatchers.IO) {
            context.savedAccountsDataStore.data.first()[accountsKey]?.let { jsonStr ->
                runCatching {
                    json.decodeFromString(serializer, jsonStr)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }
        _accountsFlow.value = accounts
    }
    
    suspend fun saveAccount(account: SavedAccount, refreshToken: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Encrypt refresh token if provided
            val encryptedToken = refreshToken?.let {
                encryptionManager.encrypt(it).getOrNull()
            }
            
            val accountToSave = account.copy(
                authToken = encryptedToken,
                lastUsedAt = System.currentTimeMillis()
            )
            
            context.savedAccountsDataStore.edit { preferences ->
                val current = preferences[accountsKey]?.let { jsonStr ->
                    runCatching { json.decodeFromString(serializer, jsonStr) }.getOrDefault(emptyList())
                } ?: emptyList()
                
                // Remove existing account with same ID
                val filtered = current.filterNot { it.userId == account.userId }
                
                // Add new account at the beginning
                val updated = (listOf(accountToSave) + filtered).take(MAX_SAVED_ACCOUNTS)
                
                preferences[accountsKey] = json.encodeToString(serializer, updated)
                _accountsFlow.value = updated
            }
            
            // Set as active account
            prefs.edit { putString(PREFS_ACTIVE_ACCOUNT, account.userId) }
            _activeAccountId.value = account.userId
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun removeAccount(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.savedAccountsDataStore.edit { preferences ->
                val current = preferences[accountsKey]?.let { jsonStr ->
                    runCatching { json.decodeFromString(serializer, jsonStr) }.getOrDefault(emptyList())
                } ?: emptyList()
                
                val updated = current.filterNot { it.userId == userId }
                preferences[accountsKey] = json.encodeToString(serializer, updated)
                _accountsFlow.value = updated
            }
            
            // If removing active account, clear it
            if (_activeAccountId.value == userId) {
                prefs.edit { remove(PREFS_ACTIVE_ACCOUNT) }
                _activeAccountId.value = null
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun switchToAccount(userId: String): Result<SavedAccount> = withContext(Dispatchers.IO) {
        val account = _accountsFlow.value.find { it.userId == userId }
            ?: return@withContext Result.failure(Exception("Account not found"))
        
        // Update last used time
        val updatedAccount = account.copy(lastUsedAt = System.currentTimeMillis())
        
        context.savedAccountsDataStore.edit { preferences ->
            val current = preferences[accountsKey]?.let { jsonStr ->
                runCatching { json.decodeFromString(serializer, jsonStr) }.getOrDefault(emptyList())
            } ?: emptyList()
            
            val updated = current.map { 
                if (it.userId == userId) updatedAccount else it 
            }.sortedByDescending { it.lastUsedAt }
            
            preferences[accountsKey] = json.encodeToString(serializer, updated)
            _accountsFlow.value = updated
        }
        
        prefs.edit { putString(PREFS_ACTIVE_ACCOUNT, userId) }
        _activeAccountId.value = userId
        
        Result.success(updatedAccount)
    }
    
    suspend fun getDecryptedToken(userId: String): String? = withContext(Dispatchers.IO) {
        val account = _accountsFlow.value.find { it.userId == userId } ?: return@withContext null
        
        account.authToken?.let { encrypted ->
            encryptionManager.decrypt(encrypted).getOrNull()
        }
    }
    
    suspend fun clearAllAccounts(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            context.savedAccountsDataStore.edit { it.clear() }
            _accountsFlow.value = emptyList()
            
            prefs.edit { remove(PREFS_ACTIVE_ACCOUNT) }
            _activeAccountId.value = null
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getActiveAccount(): SavedAccount? {
        val activeId = _activeAccountId.value ?: return null
        return _accountsFlow.value.find { it.userId == activeId }
    }
    
    suspend fun hasSavedAccounts(): Boolean {
        return _accountsFlow.value.isNotEmpty()
    }
    
    suspend fun getAccountCount(): Int {
        return _accountsFlow.value.size
    }
}
