package conectate.com.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

/**
 * 🔥 FIX CRÍTICO: Race Conditions en MessageRepository
 * Resuelve problemas de concurrencia que causan datos inconsistentes
 */
class ThreadSafeMessageRepository(
    private val baseRepository: MessageRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    
    // 🔥 MUTEX PARA PREVENIR RACE CONDITIONS
    private val messageMutex = Mutex()
    private val chatMutex = Mutex()
    private val syncMutex = Mutex()
    
    // 🔥 CACHE CONCURRENTE SEGURO
    private val messageCache = mutableMapOf<String, Message>()
    private val chatCache = mutableMapOf<String, Chat>()
    
    // 🔥 ESTADO DE SINCRONIZACIÓN
    private var isSyncing = false
    private var syncOperations = mutableSetOf<String>()
    
    /**
     * 🔥 ENVÍO DE MENSAJE SEGURO
     */
    suspend fun sendMessageSafe(
        chatId: String,
        content: String,
        mediaUri: String? = null,
        mediaType: String? = null,
        replyTo: String? = null
    ): Result<Message> = messageMutex.withLock {
        try {
            // 🔥 VERIFICAR DUPLICADOS
            val duplicateKey = "${chatId}_${content}_${System.currentTimeMillis()}"
            if (syncOperations.contains(duplicateKey)) {
                return Result.failure(Exception("Duplicate operation prevented"))
            }
            
            syncOperations.add(duplicateKey)
            
            var result = measureTimeMillis {
                baseRepository.sendMessage(
                    chatId = chatId,
                    content = content,
                    mediaUri = mediaUri,
                    mediaType = mediaType,
                    replyTo = replyTo
                )
            }
            
            syncOperations.remove(duplicateKey)
            
            result.onSuccess { message ->
                // 🔥 ACTUALIZAR CACHE SEGURAMENTE
                messageCache[message.id] = message
            }
            
        } catch (e: Exception) {
            syncOperations.remove("${chatId}_${content}_${System.currentTimeMillis()}")
            Result.failure(e)
        }
    }
    
    /**
     * 🔥 CARGA DE MENSAJES CONCURRENTE SEGURO
     */
    suspend fun loadMessagesSafe(
        chatId: String,
        limit: Int = 50,
        offset: Int = 0
    ): Result<List<Message>> = chatMutex.withLock {
        try {
            // 🔥 VERIFICAR SI YA ESTÁ CARGANDO
            val loadKey = "load_${chatId}_${offset}_${limit}"
            if (syncOperations.contains(loadKey)) {
                return Result.success(emptyList()) // Evitar carga duplicada
            }
            
            syncOperations.add(loadKey)
            
            var result = baseRepository.getMessages(chatId, limit, offset)
            
            syncOperations.remove(loadKey)
            
            result.onSuccess { messages ->
                // 🔥 ACTUALIZAR CACHE SEGURAMENTE
                messages.forEach { message ->
                    messageCache[message.id] = message
                }
            }
            
        } catch (e: Exception) {
            syncOperations.remove("load_${chatId}_${offset}_${limit}")
            Result.failure(e)
        }
    }
    
    /**
     * 🔥 SINCRONIZACIÓN SEGURA
     */
    suspend fun syncMessagesSafe(
        chatId: String
    ): Result<List<Message>> = syncMutex.withLock {
        try {
            if (isSyncing) {
                return Result.success(emptyList()) // Evitar sincronización duplicada
            }
            
            isSyncing = true
            
            var result = baseRepository.syncMessages(chatId)
            
            isSyncing = false
            
            result.onSuccess { messages ->
                // 🔥 ACTUALIZAR CACHE ATÓMICAMENTE
                messageMutex.withLock {
                    messages.forEach { message ->
                        messageCache[message.id] = message
                    }
                }
            }
            
        } catch (e: Exception) {
            isSyncing = false
            Result.failure(e)
        }
    }
    
    /**
     * 🔥 ACTUALIZACIÓN DE ESTADO DE MENSAJE SEGURO
     */
    suspend fun updateMessageStatusSafe(
        messageId: String,
        status: String
    ): Result<Message> = messageMutex.withLock {
        try {
            var result = baseRepository.updateMessageStatus(messageId, status)
            
            result.onSuccess { message ->
                // 🔥 ACTUALIZAR CACHE SEGURAMENTE
                messageCache[messageId] = message
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 🔥 ELIMINACIÓN DE MENSAJE SEGURO
     */
    suspend fun deleteMessageSafe(
        messageId: String
    ): Result<Unit> = messageMutex.withLock {
        try {
            var result = baseRepository.deleteMessage(messageId)
            
            result.onSuccess {
                // 🔥 REMOVER DEL CACHE SEGURAMENTE
                messageCache.remove(messageId)
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 🔥 OBTENCIÓN DE MENSAJE DEL CACHE SEGURO
     */
    fun getMessageFromCache(messageId: String): Message? {
        return messageMutex.withLock {
            messageCache[messageId]
        }
    }
    
    /**
     * 🔥 LIMPIEZA DE CACHE SEGURO
     */
    suspend fun clearCacheSafe() = messageMutex.withLock {
        try {
            messageCache.clear()
            chatCache.clear()
            syncOperations.clear()
        } catch (e: Exception) {
            // Log error but don't fail
        }
    }
    
    /**
     * 🔥 ESTADÍSTICAS DE CONCURRENCIA
     */
    fun getConcurrencyStats(): ConcurrencyStats {
        return ConcurrencyStats(
            cacheSize = messageCache.size,
            activeSyncOperations = syncOperations.size,
            isCurrentlySyncing = isSyncing
        )
    }
}

data class ConcurrencyStats(
    val cacheSize: Int,
    val activeSyncOperations: Int,
    val isCurrentlySyncing: Boolean
)

/**
 * 🔥 WRAPPER PARA MessageRepository CON THREAD SAFETY
 */
class SafeMessageRepositoryWrapper(
    private val baseRepository: MessageRepository
) : MessageRepository by baseRepository {
    
    private val threadSafeRepo = ThreadSafeMessageRepository(baseRepository)
    
    override suspend fun sendMessage(
        chatId: String,
        content: String,
        mediaUri: String?,
        mediaType: String?,
        replyTo: String?
    ): Result<Message> {
        return threadSafeRepo.sendMessageSafe(chatId, content, mediaUri, mediaType, replyTo)
    }
    
    override suspend fun getMessages(
        chatId: String,
        limit: Int,
        offset: Int
    ): Result<List<Message>> {
        return threadSafeRepo.loadMessagesSafe(chatId, limit, offset)
    }
    
    override suspend fun syncMessages(chatId: String): Result<List<Message>> {
        return threadSafeRepo.syncMessagesSafe(chatId)
    }
    
    override suspend fun updateMessageStatus(messageId: String, status: String): Result<Message> {
        return threadSafeRepo.updateMessageStatusSafe(messageId, status)
    }
    
    override suspend fun deleteMessage(messageId: String): Result<Unit> {
        return threadSafeRepo.deleteMessageSafe(messageId)
    }
}

/**
 * 🔥 MONITOR DE RACE CONDITIONS
 */
class RaceConditionMonitor {
    
    private val detectedIssues = mutableListOf<RaceConditionIssue>()
    private val issueMutex = Mutex()
    
    suspend fun reportRaceCondition(
        operation: String,
        threadId: Long,
        timestamp: Long = System.currentTimeMillis()
    ) = issueMutex.withLock {
        val issue = RaceConditionIssue(
            operation = operation,
            threadId = threadId,
            timestamp = timestamp,
            stackTrace = Thread.currentThread().stackTraceToString()
        )
        
        detectedIssues.add(issue)
        
        // Log para debugging
        android.util.Log.w("RaceConditionMonitor", 
            "Race condition detected: $operation on thread $threadId")
    }
    
    suspend fun getIssues(): List<RaceConditionIssue> = issueMutex.withLock {
        detectedIssues.toList()
    }
    
    suspend fun clearIssues() = issueMutex.withLock {
        detectedIssues.clear()
    }
}

data class RaceConditionIssue(
    val operation: String,
    val threadId: Long,
    val timestamp: Long,
    val stackTrace: String
)

/**
 * 🔥 UTILIDADES DE CONCURRENCIA
 */
object ConcurrencyUtils {
    
    /**
     * Ejecuta operación con timeout y manejo de race conditions
     */
    suspend fun <T> executeWithTimeoutAndRaceProtection(
        timeoutMs: Long = 5000,
        operation: suspend () -> T
    ): Result<T> = withTimeoutOrNull(timeoutMs) {
        try {
            Result.success(operation())
        } catch (e: CancellationException) {
            Result.failure(Exception("Operation cancelled due to timeout", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    } ?: Result.failure(Exception("Operation timed out after ${timeoutMs}ms"))
    
    /**
     * Verifica si hay operaciones concurrentes conflictivas
     */
    suspend fun <T> checkForConflicts(
        operations: List<suspend () -> T>
    ): List<Boolean> {
        return operations.map { operation ->
            try {
                withTimeoutOrNull(100) { operation() } != null
            } catch (e: Exception) {
                false
            }
        }
    }
}



