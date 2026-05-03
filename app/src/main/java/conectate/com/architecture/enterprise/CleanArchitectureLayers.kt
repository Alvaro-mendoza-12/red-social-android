package conectate.com.architecture.enterprise

/**
 * 🔥 ENTERPRISE CLEAN ARCHITECTURE - NIVEL INDUSTRIAL
 * Basado en arquitectura de Facebook/Meta, Instagram, TikTok
 * 
 * Principios:
 * 1. Dependency Inversion - Core no depende de Infrastructure
 * 2. Single Responsibility - Cada clase una sola razón de cambio
 * 3. Open/Closed - Abierto a extensión, cerrado a modificación
 * 4. Interface Segregation - Interfaces específicas y pequeñas
 * 5. Dependency Injection - Inversión de dependencias completa
 */

// ============================================================================
// DOMAIN LAYER - CORE BUSINESS LOGIC (Sin dependencias externas)
// ============================================================================

/**
 * Entidades de dominio puras - Sin dependencias de Android/Frameworks
 */
interface DomainEntity {
    val id: String
    val createdAt: Long
    val updatedAt: Long
}

data class UserEntity(
    override val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isVerified: Boolean,
    val isActive: Boolean,
    val followersCount: Long,
    val followingCount: Long,
    val postsCount: Long,
    override val createdAt: Long,
    override val updatedAt: Long
) : DomainEntity

data class MessageEntity(
    override val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val mediaUrl: String?,
    val mediaType: String?,
    val replyToId: String?,
    val status: MessageStatus,
    val isEdited: Boolean,
    val editHistory: List<String>,
    override val createdAt: Long,
    override val updatedAt: Long
) : DomainEntity

data class ChatEntity(
    override val id: String,
    val type: ChatType,
    val name: String?,
    val description: String?,
    val avatarUrl: String?,
    val participantIds: Set<String>,
    val lastMessageId: String?,
    val unreadCount: Map<String, Long>,
    val metadata: ChatMetadata,
    override val createdAt: Long,
    override val updatedAt: Long
) : DomainEntity

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED, DELETED
}

enum class ChatType {
    DIRECT, GROUP, COMMUNITY, BROADCAST
}

data class ChatMetadata(
    val isArchived: Boolean,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val customNotification: Boolean,
    val expirationTime: Long?
)

/**
 * Casos de uso puros - Lógica de negocio sin dependencias
 */
interface UseCase<in P, out R> {
    suspend operator fun invoke(params: P): Result<R>
}

interface NoParamsUseCase<out R> {
    suspend operator fun invoke(): Result<R>
}

// ============================================================================
// APPLICATION LAYER - USE CASES IMPLEMENTATION
// ============================================================================


class SendMessageUseCase  constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val eventBus: EventBus
) : UseCase<SendMessageUseCase.Params, MessageEntity> {
    
    data class Params(
        val chatId: String,
        val senderId: String,
        val content: String,
        val mediaUrl: String? = null,
        val mediaType: String? = null,
        val replyToId: String? = null
    )
    
    override suspend fun invoke(params: Params): Result<MessageEntity> {
        return try {
            // 1. Validaciones de negocio
            validateMessage(params)
            
            // 2. Verificar permisos
            validatePermissions(params)
            
            // 3. Crear entidad
            var message = createMessageEntity(params)
            
            // 4. Persistir
            val savedMessage = messageRepository.save(message)
            
            // 5. Actualizar chat
            chatRepository.updateLastMessage(params.chatId, savedMessage.id)
            
            // 6. Notificar participantes
            notifyParticipants(params.chatId, params.senderId, savedMessage)
            
            // 7. Emitir evento
            eventBus.emit(MessageSentEvent(savedMessage))
            
            Result.success(savedMessage)
            
        } catch (e: Exception) {
            Result.failure(MessageUseCaseException("Failed to send message", e))
        }
    }
    
    private suspend fun validateMessage(params: Params) {
        if (params.content.isBlank() && params.mediaUrl == null) {
            throw MessageValidationException("Message cannot be empty")
        }
        
        if (params.content.length > 4000) {
            throw MessageValidationException("Message too long")
        }
    }
    
    private suspend fun validatePermissions(params: Params) {
        var chat = chatRepository.findById(params.chatId)
            ?: throw ChatNotFoundException("Chat not found")
            
        if (!chat.participantIds.contains(params.senderId)) {
            throw PermissionDeniedException("User not in chat")
        }
    }
    
    private fun createMessageEntity(params: Params): MessageEntity {
        val now = System.currentTimeMillis()
        return MessageEntity(
            id = generateId(),
            chatId = params.chatId,
            senderId = params.senderId,
            content = params.content.trim(),
            mediaUrl = params.mediaUrl,
            mediaType = params.mediaType,
            replyToId = params.replyToId,
            status = MessageStatus.SENDING,
            isEdited = false,
            editHistory = emptyList(),
            createdAt = now,
            updatedAt = now
        )
    }
    
    private suspend fun notifyParticipants(chatId: String, senderId: String, message: MessageEntity) {
        var chat = chatRepository.findById(chatId) ?: return
        val participants = chat.participantIds - senderId
        
        participants.forEach { participantId ->
            notificationService.sendPushNotification(
                userId = participantId,
                title = "New message",
                body = message.content.take(100),
                data = mapOf(
                    "type" to "new_message",
                    "chatId" to chatId,
                    "messageId" to message.id
                )
            )
        }
    }
    
    private fun generateId(): String = "msg_${System.currentTimeMillis()}_${(1000..9999).random()}"
}

// ============================================================================
// INFRASTRUCTURE LAYER - IMPLEMENTACIONES CONCRETAS
// ============================================================================

/**
 * Repositorios - Interface pura en domain, implementación en infrastructure
 */
interface Repository<T : DomainEntity> {
    suspend fun save(entity: T): T
    suspend fun findById(id: String): T?
    suspend fun findAll(): List<T>
    suspend fun delete(id: String): Result<Unit>
    suspend fun exists(id: String): Boolean
}

interface MessageRepository : Repository<MessageEntity> {
    suspend fun findByChatId(chatId: String, limit: Int = 50, offset: Int = 0): List<MessageEntity>
    suspend fun findBySenderId(senderId: String, limit: Int = 50): List<MessageEntity>
    suspend fun updateStatus(messageId: String, status: MessageStatus): Result<MessageEntity>
    suspend fun search(query: String, userId: String): List<MessageEntity>
}

interface ChatRepository : Repository<ChatEntity> {
    suspend fun findByUserId(userId: String): List<ChatEntity>
    suspend fun updateLastMessage(chatId: String, messageId: String): Result<Unit>
    suspend fun updateUnreadCount(chatId: String, userId: String, count: Long): Result<Unit>
    suspend fun addParticipant(chatId: String, userId: String): Result<Unit>
    suspend fun removeParticipant(chatId: String, userId: String): Result<Unit>
}

interface UserRepository : Repository<UserEntity> {
    suspend fun findByEmail(email: String): UserEntity?
    suspend fun findByUsername(username: String): UserEntity?
    suspend fun search(query: String): List<UserEntity>
    suspend fun updateProfile(userId: String, updates: UserUpdates): Result<UserEntity>
    suspend fun followUser(followerId: String, followingId: String): Result<Unit>
    suspend fun unfollowUser(followerId: String, followingId: String): Result<Unit>
}

data class UserUpdates(
    val displayName: String?,
    val bio: String?,
    val avatarUrl: String?
)

// ============================================================================
// SERVICES - INFRASTRUCTURE
// ============================================================================

interface NotificationService {
    suspend fun sendPushNotification(userId: String, title: String, body: String, data: Map<String, String>): Result<Unit>
    suspend fun sendEmail(userId: String, subject: String, body: String): Result<Unit>
    suspend fun sendSMS(phoneNumber: String, message: String): Result<Unit>
}

interface EventBus {
    suspend fun emit(event: DomainEvent)
    fun <T : DomainEvent> subscribe(eventType: Class<T>): Flow<T>
}

interface CacheService {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String, ttl: Long = 3600): Result<Unit>
    suspend fun delete(key: String): Result<Unit>
    suspend fun clear(): Result<Unit>
}

interface FileStorageService {
    suspend fun upload(file: ByteArray, path: String, contentType: String): Result<String>
    suspend fun download(path: String): Result<ByteArray>
    suspend fun delete(path: String): Result<Unit>
    suspend fun getUrl(path: String, ttl: Long = 3600): String
}

// ============================================================================
// EVENTS - DOMAIN EVENTS
// ============================================================================

sealed class DomainEvent {
    abstract val timestamp: Long
    abstract val aggregateId: String
}

data class MessageSentEvent(
    val message: MessageEntity,
    override val timestamp: Long = System.currentTimeMillis(),
    override val aggregateId: String = message.id
) : DomainEvent()

data class UserRegisteredEvent(
    val user: UserEntity,
    override val timestamp: Long = System.currentTimeMillis(),
    override val aggregateId: String = user.id
) : DomainEvent()

data class ChatCreatedEvent(
    val chat: ChatEntity,
    override val timestamp: Long = System.currentTimeMillis(),
    override val aggregateId: String = chat.id
) : DomainEvent()

// ============================================================================
// EXCEPTIONS - DOMAIN EXCEPTIONS
// ============================================================================

sealed class DomainException(message: String, cause: Throwable? = null) : Exception(message, cause)

class MessageValidationException(message: String) : DomainException(message)
class ChatNotFoundException(message: String) : DomainException(message)
class PermissionDeniedException(message: String) : DomainException(message)
class UserNotFoundException(message: String) : DomainException(message)
class MessageUseCaseException(message: String, cause: Throwable? = null) : DomainException(message, cause)

// ============================================================================
// VALUE OBJECTS - INMUTABLES
// ============================================================================

@JvmInline
value class Email(val value: String) {
    init {
        require(value.isNotBlank()) { "Email cannot be blank" }
        require(value.contains("@")) { "Invalid email format" }
    }
}

@JvmInline
value class Username(val value: String) {
    init {
        require(value.isNotBlank()) { "Username cannot be blank" }
        require(value.length in 3..30) { "Username must be 3-30 characters" }
        require(value.matches(Regex("^[a-zA-Z0-9_]+$"))) { "Username can only contain letters, numbers, and underscores" }
    }
}

@JvmInline
value class MessageContent(val value: String) {
    init {
        require(value.isNotBlank()) { "Message cannot be blank" }
        require(value.length <= 4000) { "Message too long" }
    }
}

// ============================================================================
// CONFIGURATION - ENTERPRISE CONFIG
// ============================================================================

data class EnterpriseConfig(
    val maxMessageLength: Int = 4000,
    val maxFileSize: Long = 100 * 1024 * 1024, // 100MB
    val cacheDefaultTtl: Long = 3600, // 1 hour
    val maxChatParticipants: Int = 1000,
    val messageRetentionDays: Int = 365,
    val enableMessageEncryption: Boolean = true,
    val enableRateLimiting: Boolean = true,
    val maxRequestsPerMinute: Int = 100
)

// ============================================================================
// VALIDATORS - ENTERPRISE VALIDATION
// ============================================================================

object EnterpriseValidators {
    
    fun validateEmail(email: String): Email {
        return Email(email.lowercase().trim())
    }
    
    fun validateUsername(username: String): Username {
        return Username(username.lowercase().trim())
    }
    
    fun validateMessageContent(content: String): MessageContent {
        return MessageContent(content.trim())
    }
    
    fun validateFileSize(size: Long, maxSize: Long = 100 * 1024 * 1024): Long {
        require(size > 0) { "File size must be positive" }
        require(size <= maxSize) { "File too large" }
        return size
    }
    
    fun validateImageDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid dimensions" }
        require(width <= 4096 && height <= 4096) { "Image too large" }
        return Pair(width, height)
    }
}



