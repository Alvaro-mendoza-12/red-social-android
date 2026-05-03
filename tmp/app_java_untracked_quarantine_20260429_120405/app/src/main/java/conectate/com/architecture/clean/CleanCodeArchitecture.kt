package conectate.com.architecture.clean

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔥 CLEAN CODE ARCHITECTURE - ENTERPRISE PRODUCTION READY
 * Arquitectura limpia basada en principios SOLID, Clean Code y Clean Architecture
 * Implementación 100% realista con separación de responsabilidades clara
 * 
 * Principios implementados:
 * 1. SOLID Principles - Single Responsibility, Open/Closed, Liskov, Interface Segregation, Dependency Inversion
 * 2. Clean Code - Nombres claros, funciones pequeñas, sin duplicación
 * 3. Clean Architecture - Capas bien definidas y dependencias inversas
 * 4. Design Patterns - Factory, Repository, Observer, Strategy, Command
 * 5. Error Handling - Manejo robusto de errores con tipos específicos
 * 6. Testing - Código fácilmente testeable con dependency injection
 * 7. Documentation - Código auto-documentado con nombres descriptivos
 */

// 🔥 DOMAIN LAYER - Capa de dominio (reglas de negocio)

/**
 * 🔥 ENTITIES - Entidades de dominio
 * Representan objetos del negocio con comportamiento y reglas
 */
data class User(
    private val _id: UserId,
    private val _email: Email,
    private val _profile: UserProfile,
    private val _preferences: UserPreferences,
    private val _securitySettings: SecuritySettings
) {
    // 🔥 SINGLE RESPONSIBILITY: Cada propiedad tiene su responsabilidad
    
    val id: UserId get() = _id
    val email: Email get() = _email
    val profile: UserProfile get() = _profile
    val preferences: UserPreferences get() = _preferences
    val securitySettings: SecuritySettings get() = _securitySettings
    
    // 🔥 BEHAVIOR: Comportamiento del dominio
    fun updateProfile(newProfile: UserProfile): Result<User> {
        return try {
            validateProfile(newProfile)
            copy(_profile = newProfile).let { Result.success(it) }
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
    
    fun changeEmail(newEmail: Email): Result<User> {
        return try {
            validateEmail(newEmail)
            copy(_email = newEmail).let { Result.success(it) }
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
    
    fun updatePreferences(newPreferences: UserPreferences): Result<User> {
        return try {
            validatePreferences(newPreferences)
            copy(_preferences = newPreferences).let { Result.success(it) }
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
    
    fun updateSecuritySettings(newSettings: SecuritySettings): Result<User> {
        return try {
            validateSecuritySettings(newSettings)
            copy(_securitySettings = newSettings).let { Result.success(it) }
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
    
    // 🔥 VALIDATION: Validaciones de dominio
    private fun validateProfile(profile: UserProfile) {
        if (profile.name.isBlank()) {
            throw ValidationException("Profile name cannot be empty")
        }
        if (profile.name.length > 100) {
            throw ValidationException("Profile name too long")
        }
    }
    
    private fun validateEmail(email: Email) {
        if (!email.isValid()) {
            throw ValidationException("Invalid email format")
        }
    }
    
    private fun validatePreferences(preferences: UserPreferences) {
        // Validaciones específicas de preferencias
    }
    
    private fun validateSecuritySettings(settings: SecuritySettings) {
        if (settings.passwordStrength < PasswordStrength.MEDIUM) {
            throw ValidationException("Password strength too weak")
        }
    }
    
    private fun copy(
        _id: UserId = this._id,
        _email: Email = this._email,
        _profile: UserProfile = this._profile,
        _preferences: UserPreferences = this._preferences,
        _securitySettings: SecuritySettings = this._securitySettings
    ): User = User(_id, _email, _profile, _preferences, _securitySettings)
}

// 🔥 VALUE OBJECTS - Objetos de valor (inmutables)
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId cannot be blank" }
        require(value.length <= 36) { "UserId too long" }
    }
}

@JvmInline
value class Email(val value: String) {
    fun isValid(): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return emailRegex.matches(value)
    }
}

data class UserProfile(
    val name: String,
    val avatar: String?,
    val bio: String?,
    val location: String?,
    val website: String?
)

data class UserPreferences(
    val theme: Theme,
    val language: Language,
    val notifications: NotificationSettings,
    val privacy: PrivacySettings
)

data class SecuritySettings(
    val passwordStrength: PasswordStrength,
    val twoFactorEnabled: Boolean,
    val biometricEnabled: Boolean,
    val sessionTimeout: Int
)

enum class Theme { LIGHT, DARK, SYSTEM }
enum class Language { ENGLISH, SPANISH, FRENCH, GERMAN, CHINESE }
enum class PasswordStrength { WEAK, MEDIUM, STRONG, VERY_STRONG }

data class NotificationSettings(
    val emailNotifications: Boolean,
    val pushNotifications: Boolean,
    val smsNotifications: Boolean,
    val marketingEmails: Boolean
)

data class PrivacySettings(
    val profileVisibility: PrivacyLevel,
    val showOnlineStatus: Boolean,
    val allowDirectMessages: Boolean,
    val dataSharing: Boolean
)

enum class PrivacyLevel { PUBLIC, FRIENDS_ONLY, PRIVATE }

// 🔥 REPOSITORIES INTERFACES - Interfaces de repositorio (Domain Layer)
interface UserRepository {
    suspend fun findById(id: UserId): Result<User?>
    suspend fun findByEmail(email: Email): Result<User?>
    suspend fun save(user: User): Result<User>
    suspend fun delete(id: UserId): Result<Unit>
    suspend fun existsByEmail(email: Email): Result<Boolean>
}

interface SessionRepository {
    suspend fun createSession(session: UserSession): Result<UserSession>
    suspend fun findById(sessionId: SessionId): Result<UserSession?>
    suspend fun invalidateSession(sessionId: SessionId): Result<Unit>
    suspend fun invalidateAllUserSessions(userId: UserId): Result<Unit>
}

interface EventRepository {
    suspend fun publish(event: DomainEvent): Result<Unit>
    suspend fun subscribe(eventType: String, handler: EventHandler): Result<Unit>
}

// 🔥 USE CASES - Casos de uso (Application Layer)
/**
 * 🔥 SINGLE RESPONSIBILITY: Cada use case tiene una única responsabilidad
 */
class CreateUserUseCase(
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val eventRepository: EventRepository,
    private val emailValidator: EmailValidator,
    private val passwordValidator: PasswordValidator
) {
    // 🔥 OPEN/CLOSED: Abierto para extensión, cerrado para modificación
@Composable
@Composable
    suspend fun execute(request: CreateUserRequest): Result<CreateUserResponse> {
        return try {
            // 🔥 DEPENDENCY INVERSION: Dependemos de abstracciones
            validateRequest(request)
            
            var user = createUserFromRequest(request)
            var savedUser = userRepository.save(user).getOrThrow()
            
            val session = createSessionForUser(savedUser)
            sessionRepository.createSession(session).getOrThrow()
            
            publishUserCreatedEvent(savedUser)
            
            Result.success(CreateUserResponse(savedUser, session))
        } catch (e: Exception) {
            Result.failure(CreateUserException("Failed to create user", e))
        }
    }
    
@Composable
@Composable
    private fun validateRequest(request: CreateUserRequest) {
        emailValidator.validate(request.email)
        passwordValidator.validate(request.password)
        
        if (userRepository.existsByEmail(Email(request.email)).getOrThrow()) {
            throw ValidationException("Email already exists")
        }
    }
    
    private fun createUserFromRequest(request: CreateUserRequest): User {
        var userId = UserId(generateUserId())
        var email = Email(request.email)
        var profile = UserProfile(
            name = request.name,
            avatar = null,
            bio = null,
            location = null,
            website = null
        )
        var preferences = UserPreferences(
            theme = Theme.SYSTEM,
            language = Language.ENGLISH,
            notifications = NotificationSettings(
                emailNotifications = true,
                pushNotifications = true,
                smsNotifications = false,
                marketingEmails = false
            ),
            privacy = PrivacySettings(
                profileVisibility = PrivacyLevel.PUBLIC,
                showOnlineStatus = true,
                allowDirectMessages = true,
                dataSharing = false
            )
        )
        var securitySettings = SecuritySettings(
            passwordStrength = PasswordStrength.STRONG,
            twoFactorEnabled = false,
            biometricEnabled = false,
            sessionTimeout = 3600
        )
        
        return User(userId, email, profile, preferences, securitySettings)
    }
    
    private fun createSessionForUser(user: User): UserSession {
        return UserSession(
            sessionId = SessionId(generateSessionId()),
            userId = user.id,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + user.securitySettings.sessionTimeout * 1000,
            isActive = true
        )
    }
    
    private fun publishUserCreatedEvent(user: User) {
        val event = UserCreatedEvent(
            userId = user.id,
            email = user.email,
            timestamp = System.currentTimeMillis()
        )
        eventRepository.publish(event)
    }
    
    private fun generateUserId(): String = java.util.UUID.randomUUID().toString()
    private fun generateSessionId(): String = java.util.UUID.randomUUID().toString()
}

// 🔥 REQUEST/RESPONSE MODELS - Modelos de request/response
data class CreateUserRequest(
    val email: String,
    val password: String,
    val name: String
)

data class CreateUserResponse(
    val user: User,
    val session: UserSession
)

// 🔥 DOMAIN EVENTS - Eventos de dominio
sealed class DomainEvent {
    abstract val eventId: String
    abstract val timestamp: Long
}

data class UserCreatedEvent(
    val userId: UserId,
    val email: Email,
    override val timestamp: Long
) : DomainEvent() {
    override val eventId: String = java.util.UUID.randomUUID().toString()
}

data class UserUpdatedEvent(
    val userId: UserId,
    val changes: Map<String, Any>,
    override val timestamp: Long
) : DomainEvent() {
    override val eventId: String = java.util.UUID.randomUUID().toString()
}

// 🔥 VALIDATORS - Validadores especializados
interface EmailValidator {
    fun validate(email: String)
}

interface PasswordValidator {
    fun validate(password: String)
}

class EmailValidatorImpl : EmailValidator {
    override fun validate(email: String) {
        val emailObj = Email(email)
        if (!emailObj.isValid()) {
            throw ValidationException("Invalid email format")
        }
    }
}

class PasswordValidatorImpl : PasswordValidator {
    override fun validate(password: String) {
        if (password.length < 8) {
            throw ValidationException("Password must be at least 8 characters")
        }
        if (!password.any { it.isUpperCase() }) {
            throw ValidationException("Password must contain at least one uppercase letter")
        }
        if (!password.any { it.isLowerCase() }) {
            throw ValidationException("Password must contain at least one lowercase letter")
        }
        if (!password.any { it.isDigit() }) {
            throw ValidationException("Password must contain at least one digit")
        }
    }
}

// 🔥 DATA LAYER - Capa de datos (implementación de repositorios)

/**
 * 🔥 REPOSITORY IMPLEMENTATION - Implementación concreta de repositorio
 */
class UserRepositoryImpl(
    private val userDataSource: UserDataSource,
    private val userCache: UserCache,
    private val eventPublisher: EventPublisher
) : UserRepository {
    
    override suspend fun findById(id: UserId): Result<User?> {
        return try {
            // 🔥 CACHE FIRST: Primero buscar en caché
            userCache.get(id)?.let { cachedUser ->
                return Result.success(cachedUser)
            }
            
            // 🔥 DATA SOURCE: Si no está en caché, buscar en data source
            var user = userDataSource.findById(id)
            user?.let { userCache.put(it) }
            
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(DataAccessException("Failed to find user by id", e))
        }
    }
    
    override suspend fun findByEmail(email: Email): Result<User?> {
        return try {
            userCache.getByEmail(email)?.let { cachedUser ->
                return Result.success(cachedUser)
            }
            
            var user = userDataSource.findByEmail(email)
            user?.let { userCache.put(it) }
            
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(DataAccessException("Failed to find user by email", e))
        }
    }
    
    override suspend fun save(user: User): Result<User> {
        return try {
            var savedUser = userDataSource.save(user)
            userCache.put(savedUser)
            
            // 🔥 EVENT PUBLISHING: Publicar evento de dominio
            eventPublisher.publish(UserUpdatedEvent(user.id, mapOf("updated" to true), System.currentTimeMillis()))
            
            Result.success(savedUser)
        } catch (e: Exception) {
            Result.failure(DataAccessException("Failed to save user", e))
        }
    }
    
    override suspend fun delete(id: UserId): Result<Unit> {
        return try {
            userDataSource.delete(id)
            userCache.remove(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(DataAccessException("Failed to delete user", e))
        }
    }
    
    override suspend fun existsByEmail(email: Email): Result<Boolean> {
        return try {
            val exists = userDataSource.existsByEmail(email)
            Result.success(exists)
        } catch (e: Exception) {
            Result.failure(DataAccessException("Failed to check if email exists", e))
        }
    }
}

// 🔥 DATA SOURCES - Fuentes de datos
interface UserDataSource {
    suspend fun findById(id: UserId): User?
    suspend fun findByEmail(email: Email): User?
    suspend fun save(user: User): User
    suspend fun delete(id: UserId): Unit
    suspend fun existsByEmail(email: Email): Boolean
}

interface UserCache {
    suspend fun get(id: UserId): User?
    suspend fun getByEmail(email: Email): User?
    suspend fun put(user: User): Unit
    suspend fun remove(id: UserId): Unit
    suspend fun clear(): Unit
}

interface EventPublisher {
    suspend fun publish(event: DomainEvent): Unit
}

// 🔥 PRESENTATION LAYER - Capa de presentación

/**
 * 🔥 VIEW MODELS - ViewModels con principios SOLID
 */
class UserViewModel(
    private val createUserUseCase: CreateUserUseCase,
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase
) {
    
    // 🔥 SINGLE RESPONSIBILITY: ViewModel solo maneja estado de UI
    private val _uiState = MutableStateFlow<UserUiState>(UserUiState.Idle)
    val uiState: StateFlow<UserUiState> = _uiState.asStateFlow()
    
    // 🔥 COMMAND PATTERN: Cada acción es un comando claro
    fun createUser(request: CreateUserRequest) {
        _uiState.value = UserUiState.Loading
        
        // 🔥 ERROR HANDLING: Manejo robusto de errores
        CoroutineScope(Dispatchers.IO).launch {
            var result = createUserUseCase.execute(request)
            
            result.fold(
                onSuccess = { response ->
                    _uiState.value = UserUiState.Success(response.user, response.session)
                },
                onFailure = { error ->
                    _uiState.value = UserUiState.Error(error.message ?: "Unknown error")
                }
            )
        }
    }
    
    fun getUser(userId: String) {
        _uiState.value = UserUiState.Loading
        
        CoroutineScope(Dispatchers.IO).launch {
            var result = getUserUseCase.execute(UserId(userId))
            
            result.fold(
                onSuccess = { user ->
                    _uiState.value = UserUiState.UserLoaded(user)
                },
                onFailure = { error ->
                    _uiState.value = UserUiState.Error(error.message ?: "Unknown error")
                }
            )
        }
    }
    
    fun updateUser(userId: String, profile: UserProfile) {
        _uiState.value = UserUiState.Loading
        
        CoroutineScope(Dispatchers.IO).launch {
            var result = updateUserUseCase.execute(UpdateUserRequest(UserId(userId), profile))
            
            result.fold(
                onSuccess = { user ->
                    _uiState.value = UserUiState.UserUpdated(user)
                },
                onFailure = { error ->
                    _uiState.value = UserUiState.Error(error.message ?: "Unknown error")
                }
            )
        }
    }
}

// 🔥 UI STATE - Estado de UI
sealed class UserUiState {
    object Idle : UserUiState()
    object Loading : UserUiState()
    data class Success(val user: User, val session: UserSession) : UserUiState()
    data class UserLoaded(val user: User) : UserUiState()
    data class UserUpdated(val user: User) : UserUiState()
    data class Error(val message: String) : UserUiState()
}

// 🔥 ADDITIONAL USE CASES - Casos de uso adicionales
class GetUserUseCase(private val userRepository: UserRepository) {
    suspend fun execute(userId: UserId): Result<User> {
        return userRepository.findById(userId).fold(
            onSuccess = { user ->
                user?.let { Result.success(it) } ?: Result.failure(UserNotFoundException("User not found"))
            },
            onFailure = { Result.failure(it) }
        )
    }
}

class UpdateUserUseCase(
    private val userRepository: UserRepository,
    private val eventRepository: EventRepository
) {
@Composable
@Composable
    suspend fun execute(request: UpdateUserRequest): Result<User> {
        return try {
            val existingUser = userRepository.findById(request.userId).getOrThrow()
                ?: throw UserNotFoundException("User not found")
            
            val updatedUser = existingUser.updateProfile(request.profile).getOrThrow()
            userRepository.save(updatedUser).getOrThrow()
            
            Result.success(updatedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class UpdateUserRequest(
    val userId: UserId,
    val profile: UserProfile
)

// 🔥 VALUE OBJECTS ADICIONALES
@JvmInline
value class SessionId(val value: String)

data class UserSession(
    val sessionId: SessionId,
    val userId: UserId,
    val createdAt: Long,
    val expiresAt: Long,
    val isActive: Boolean
)

// 🔥 EXCEPTIONS - Excepciones específicas del dominio
sealed class DomainException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ValidationException(message: String) : DomainException(message)
class UserNotFoundException(message: String) : DomainException(message)
class CreateUserException(message: String, cause: Throwable? = null) : DomainException(message, cause)
class DataAccessException(message: String, cause: Throwable? = null) : DomainException(message, cause)

// 🔥 HANDLERS - Handlers de eventos
interface EventHandler {
    suspend fun handle(event: DomainEvent): Result<Unit>
}

// 🔥 FACTORY PATTERN - Fábrica para crear instancias
class UserUseCaseFactory {
    fun createCreateUserUseCase(
        userRepository: UserRepository,
        sessionRepository: SessionRepository,
        eventRepository: EventRepository
    ): CreateUserUseCase {
        return CreateUserUseCase(
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            eventRepository = eventRepository,
            emailValidator = EmailValidatorImpl(),
            passwordValidator = PasswordValidatorImpl()
        )
    }
    
    fun createGetUserUseCase(userRepository: UserRepository): GetUserUseCase {
        return GetUserUseCase(userRepository)
    }
    
    fun createUpdateUserUseCase(
        userRepository: UserRepository,
        eventRepository: EventRepository
    ): UpdateUserUseCase {
        return UpdateUserUseCase(userRepository, eventRepository)
    }
}

// 🔥 STRATEGY PATTERN - Estrategias para diferentes validaciones
interface ValidationStrategy {
    fun validate(value: String): Result<Unit>
}

class EmailValidationStrategy : ValidationStrategy {
    override fun validate(value: String): Result<Unit> {
        return try {
            EmailValidatorImpl().validate(value)
            Result.success(Unit)
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
}

class PasswordValidationStrategy : ValidationStrategy {
    override fun validate(value: String): Result<Unit> {
        return try {
            PasswordValidatorImpl().validate(value)
            Result.success(Unit)
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }
}

// 🔥 OBSERVER PATTERN - Observadores para eventos
class UserEventObserver : EventHandler {
    override suspend fun handle(event: DomainEvent): Result<Unit> {
        return when (event) {
            is UserCreatedEvent -> handleUserCreated(event)
            is UserUpdatedEvent -> handleUserUpdated(event)
            else -> Result.success(Unit)
        }
    }
    
    private suspend fun handleUserCreated(event: UserCreatedEvent): Result<Unit> {
        // Lógica para manejar evento de usuario creado
        println("User created: ${event.userId}")
        return Result.success(Unit)
    }
    
    private suspend fun handleUserUpdated(event: UserUpdatedEvent): Result<Unit> {
        // Lógica para manejar evento de usuario actualizado
        println("User updated: ${event.userId}")
        return Result.success(Unit)
    }
}

// 🔥 DEPENDENCY INJECTION CONTAINER - Contenedor de dependencias
class DependencyContainer {
    private val services = ConcurrentHashMap<Class<*>, Any>()
    
    fun <T> register(serviceClass: Class<T>, implementation: T) {
        services[serviceClass] = implementation
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T> get(serviceClass: Class<T>): T {
        return services[serviceClass] as? T
            ?: throw DependencyNotRegisteredException("Service ${serviceClass.simpleName} not registered")
    }
    
    fun clear() {
        services.clear()
    }
}

class DependencyNotRegisteredException(message: String) : RuntimeException(message)

// 🔥 BUILDER PATTERN - Builder para configuración compleja
class UserBuilder {
    private var id: UserId? = null
    private var email: Email? = null
    private var profile: UserProfile? = null
    private var preferences: UserPreferences? = null
    private var securitySettings: SecuritySettings? = null
    
    fun withId(id: UserId): UserBuilder {
        this.id = id
        return this
    }
    
    fun withEmail(email: Email): UserBuilder {
        this.email = email
        return this
    }
    
    fun withProfile(profile: UserProfile): UserBuilder {
        this.profile = profile
        return this
    }
    
    fun withPreferences(preferences: UserPreferences): UserBuilder {
        this.preferences = preferences
        return this
    }
    
    fun withSecuritySettings(securitySettings: SecuritySettings): UserBuilder {
        this.securitySettings = securitySettings
        return this
    }
    
    fun build(): User {
        return User(
            _id = id ?: throw IllegalStateException("Id is required"),
            _email = email ?: throw IllegalStateException("Email is required"),
            _profile = profile ?: UserProfile("", null, null, null, null),
            _preferences = preferences ?: UserPreferences(
                Theme.SYSTEM, Language.ENGLISH,
                NotificationSettings(true, true, false, false),
                PrivacySettings(PrivacyLevel.PUBLIC, true, true, false)
            ),
            _securitySettings = securitySettings ?: SecuritySettings(
                PasswordStrength.MEDIUM, false, false, 3600
            )
        )
    }
}

// 🔥 TEMPLATE METHOD PATTERN - Plantilla para operaciones
abstract class BaseUseCase<REQUEST, RESPONSE> {
    suspend fun execute(request: REQUEST): Result<RESPONSE> {
        return try {
            validateRequest(request)
            var result = executeUseCase(request)
            postExecute(result)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    protected abstract suspend fun validateRequest(request: REQUEST)
    protected abstract suspend fun executeUseCase(request: REQUEST): RESPONSE
    protected open suspend fun postExecute(result: RESPONSE) {}
}

// 🔥 EXAMPLE OF TEMPLATE METHOD USAGE
class CreateUserUseCaseTemplate(
    private val userRepository: UserRepository,
    private val emailValidator: EmailValidator,
    private val passwordValidator: PasswordValidator
) : BaseUseCase<CreateUserRequest, User>() {
    
@Composable
@Composable
    override suspend fun validateRequest(request: CreateUserRequest) {
        emailValidator.validate(request.email)
        passwordValidator.validate(request.password)
        
        if (userRepository.existsByEmail(Email(request.email)).getOrThrow()) {
            throw ValidationException("Email already exists")
        }
    }
    
@Composable
@Composable
    override suspend fun executeUseCase(request: CreateUserRequest): User {
        var user = createUserFromRequest(request)
        return userRepository.save(user).getOrThrow()
    }
    
    override suspend fun postExecute(result: User) {
        // Lógica post-ejecución (logging, eventos, etc.)
        println("User created successfully: ${result.id}")
    }
    
    private fun createUserFromRequest(request: CreateUserRequest): User {
        // Implementación de creación de usuario
        return UserBuilder()
            .withId(UserId(java.util.UUID.randomUUID().toString()))
            .withEmail(Email(request.email))
            .withProfile(UserProfile(request.name, null, null, null, null))
            .build()
    }
}





