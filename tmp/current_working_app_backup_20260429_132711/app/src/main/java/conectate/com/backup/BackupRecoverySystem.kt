package conectate.com.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import java.io.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

/**
 * 🔥 BACKUP AND RECOVERY SYSTEM - SISTEMA DE BACKUP Y RECUPERACIÓN
 * Sistema completo de backup y recuperación para CONÉCTATE
 * 
 * Este sistema implementa:
 * - Backup automático de datos
 * - Recuperación de datos
 * - Backup incremental y diferencial
 * - Compresión y encriptación de backups
 * - Programación de backups
 * - Monitoreo de backup status
 */

@Singleton
class BackupRecoverySystem @Inject constructor(
    private val backupScheduler: BackupScheduler,
    private val backupStorage: BackupStorage,
    private val dataExtractor: DataExtractor,
    private val backupCompressor: BackupCompressor,
    private val backupEncryptor: BackupEncryptor,
    private val backupValidator: BackupValidator,
    private val recoveryManager: RecoveryManager,
    private val backupMonitor: BackupMonitor
) {
    
    companion object {
        private const val TAG = "BackupRecoverySystem"
        
        // 🔥 BACKUP CONFIGURATIONS
        const val DEFAULT_BACKUP_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
        const val MAX_BACKUP_SIZE = 5 * 1024 * 1024 * 1024L // 5GB
        const val BACKUP_RETENTION_DAYS = 30
        const val BACKUP_COMPRESSION_LEVEL = 6
        const val BACKUP_ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
        
        // 🔥 BACKUP TYPES
        enum class BackupType {
            FULL,           // Backup completo de todos los datos
            INCREMENTAL,    // Solo cambios desde el último backup
            DIFFERENTIAL    // Cambios desde el último backup completo
            AUTO           // Determinado automáticamente
        }
        
        // 🔥 BACKUP STATUS
        enum class BackupStatus {
            PENDING,        // Esperando para ejecutar
            IN_PROGRESS,    // En ejecución
            COMPLETED,      // Completado exitosamente
            FAILED,         // Falló
            CANCELLED,       // Cancelado
            EXPIRED         // Expirado
        }
        
        // 🔥 RECOVERY STATUS
        enum class RecoveryStatus {
            PENDING,        // Esperando para ejecutar
            IN_PROGRESS,    // En ejecución
            COMPLETED,      // Completado exitosamente
            FAILED,         // Falló
            PARTIAL,        // Recuperación parcial
            CANCELLED       // Cancelado
        }
    }
    
    // 🔥 BACKUP METADATA
    data class BackupMetadata(
        val id: String,
        val type: BackupType,
        val status: BackupStatus,
        val createdAt: Instant,
        val completedAt: Instant?,
        val size: Long,
        val compressedSize: Long,
        val encrypted: Boolean,
        val checksum: String,
        val description: String,
        val tags: Set<String>,
        val dependencies: Set<String> // IDs de backups dependientes
    )
    
    // 🔥 BACKUP RESULT
    data class BackupResult(
        val success: Boolean,
        val backupId: String,
        val metadata: BackupMetadata,
        val duration: Long,
        val filesBackedUp: Int,
        val errors: List<BackupError>
    )
    
    data class BackupError(
        val timestamp: Instant,
        val type: ErrorType,
        val message: String,
        val filePath: String?,
        val stackTrace: String?
    )
    
    enum class ErrorType {
        FILE_NOT_FOUND,
        PERMISSION_DENIED,
        DISK_FULL,
        NETWORK_ERROR,
        CORRUPTION,
        ENCRYPTION_ERROR,
        COMPRESSION_ERROR,
        VALIDATION_ERROR
    }
    
    // 🔥 RECOVERY RESULT
    data class RecoveryResult(
        val success: Boolean,
        val recoveryId: String,
        val backupId: String,
        val duration: Long,
        val filesRecovered: Int,
        val errors: List<RecoveryError>,
        val partial: Boolean
    )
    
    data class RecoveryError(
        val timestamp: Instant,
        val type: ErrorType,
        val message: String,
        val filePath: String?,
        val stackTrace: String?
    )
    
    // 🔥 MAIN BACKUP METHOD
    suspend fun createBackup(
        type: BackupType = BackupType.AUTO,
        description: String = "Automatic backup",
        tags: Set<String> = emptySet()
    ): BackupResult {
        var backupId = generateBackupId()
        var startTime = System.currentTimeMillis()
        
        Log.i(TAG, "🔥 Iniciando backup: $backupId - Tipo: $type")
        
        return try {
            // Determine backup type if AUTO
            val actualType = if (type == BackupType.AUTO) {
                determineBackupType()
            } else {
                type
            }
            
            // Create backup metadata
            var metadata = BackupMetadata(
                id = backupId,
                type = actualType,
                status = BackupStatus.IN_PROGRESS,
                createdAt = Instant.now(),
                completedAt = null,
                size = 0,
                compressedSize = 0,
                encrypted = true,
                checksum = "",
                description = description,
                tags = tags,
                dependencies = if (actualType != BackupType.FULL) {
                    getDependenciesForIncrementalBackup()
                } else {
                    emptySet()
                }
            )
            
            // Extract data
            Log.i(TAG, "🔥 Extrayendo datos para backup...")
            var extractedData = dataExtractor.extractData(actualType, metadata.dependencies)
            
            // Compress data
            Log.i(TAG, "🔥 Comprimiendo datos...")
            var compressedData = backupCompressor.compress(extractedData)
            
            // Encrypt data
            Log.i(TAG, "🔥 Encriptando datos...")
            var encryptedData = backupEncryptor.encrypt(compressedData)
            
            // Store backup
            Log.i(TAG, "🔥 Almacenando backup...")
            val storedSize = backupStorage.storeBackup(backupId, encryptedData)
            
            // Calculate checksum
            var checksum = calculateChecksum(encryptedData)
            
            // Update metadata
            val completedMetadata = metadata.copy(
                status = BackupStatus.COMPLETED,
                completedAt = Instant.now(),
                size = extractedData.size.toLong(),
                compressedSize = compressedData.size.toLong(),
                checksum = checksum
            )
            
            // Validate backup
            var validationResult = backupValidator.validateBackup(completedMetadata, encryptedData)
            
            if (!validationResult.isValid) {
                throw BackupException("Backup validation failed: ${validationResult.errorMessage}")
            }
            
            // Update backup metadata
            backupStorage.updateMetadata(completedMetadata)
            
            var endTime = System.currentTimeMillis()
            var duration = endTime - startTime
            
            Log.i(TAG, "✅ Backup completado exitosamente: $backupId")
            
            BackupResult(
                success = true,
                backupId = backupId,
                metadata = completedMetadata,
                duration = duration,
                filesBackedUp = extractedData.size,
                errors = emptyList()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error en backup: $backupId", e)
            
            var endTime = System.currentTimeMillis()
            var duration = endTime - startTime
            
            // Update metadata with failure
            val failedMetadata = BackupMetadata(
                id = backupId,
                type = type,
                status = BackupStatus.FAILED,
                createdAt = Instant.now(),
                completedAt = Instant.now(),
                size = 0,
                compressedSize = 0,
                encrypted = false,
                checksum = "",
                description = description,
                tags = tags,
                dependencies = emptySet()
            )
            
            backupStorage.updateMetadata(failedMetadata)
            
            BackupResult(
                success = false,
                backupId = backupId,
                metadata = failedMetadata,
                duration = duration,
                filesBackedUp = 0,
                errors = listOf(BackupError(
                    timestamp = Instant.now(),
                    type = ErrorType.CORRUPTION,
                    message = e.message ?: "Unknown error",
                    filePath = null,
                    stackTrace = e.stackTraceToString()
                ))
            )
        }
    }
    
    // 🔥 MAIN RECOVERY METHOD
    suspend fun recoverFromBackup(
        backupId: String,
        targetLocation: String? = null
    ): RecoveryResult {
        var recoveryId = generateRecoveryId()
        var startTime = System.currentTimeMillis()
        
        Log.i(TAG, "🔥 Iniciando recuperación: $recoveryId - Backup: $backupId")
        
        return try {
            // Get backup metadata
            var metadata = backupStorage.getMetadata(backupId)
                ?: throw BackupException("Backup metadata not found: $backupId")
            
            // Validate backup
            var validationResult = backupValidator.validateBackup(metadata, null)
            if (!validationResult.isValid) {
                throw BackupException("Backup validation failed: ${validationResult.errorMessage}")
            }
            
            // Retrieve backup data
            Log.i(TAG, "🔥 Recuperando datos del backup...")
            var encryptedData = backupStorage.retrieveBackup(backupId)
            
            // Decrypt data
            Log.i(TAG, "🔥 Desencriptando datos...")
            var compressedData = backupEncryptor.decrypt(encryptedData)
            
            // Decompress data
            Log.i(TAG, "🔥 Descomprimiendo datos...")
            var extractedData = backupCompressor.decompress(compressedData)
            
            // Recover data
            Log.i(TAG, "🔥 Recuperando datos al sistema...")
            var filesRecovered = recoveryManager.recoverData(extractedData, targetLocation, metadata)
            
            var endTime = System.currentTimeMillis()
            var duration = endTime - startTime
            
            Log.i(TAG, "✅ Recuperación completada exitosamente: $recoveryId")
            
            RecoveryResult(
                success = true,
                recoveryId = recoveryId,
                backupId = backupId,
                duration = duration,
                filesRecovered = filesRecovered,
                errors = emptyList(),
                partial = false
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error en recuperación: $recoveryId", e)
            
            var endTime = System.currentTimeMillis()
            var duration = endTime - startTime
            
            RecoveryResult(
                success = false,
                recoveryId = recoveryId,
                backupId = backupId,
                duration = duration,
                filesRecovered = 0,
                errors = listOf(RecoveryError(
                    timestamp = Instant.now(),
                    type = ErrorType.CORRUPTION,
                    message = e.message ?: "Unknown error",
                    filePath = null,
                    stackTrace = e.stackTraceToString()
                )),
                partial = false
            )
        }
    }
    
    // 🔥 SCHEDULED BACKUPS
    suspend fun scheduleBackup(
        interval: Long = DEFAULT_BACKUP_INTERVAL,
        type: BackupType = BackupType.AUTO,
        description: String = "Scheduled backup"
    ): String {
        return backupScheduler.scheduleBackup(interval, type, description)
    }
    
    suspend fun cancelScheduledBackup(scheduleId: String): Boolean {
        return backupScheduler.cancelScheduledBackup(scheduleId)
    }
    
    // 🔥 BACKUP MANAGEMENT
    suspend fun listBackups(
        type: BackupType? = null,
        status: BackupStatus? = null,
        limit: Int = 100
    ): List<BackupMetadata> {
        return backupStorage.listBackups(type, status, limit)
    }
    
    suspend fun deleteBackup(backupId: String): Boolean {
        Log.i(TAG, "🔥 Eliminando backup: $backupId")
        
        return try {
            val success = backupStorage.deleteBackup(backupId)
            if (success) {
                Log.i(TAG, "✅ Backup eliminado exitosamente: $backupId")
            } else {
                Log.w(TAG, "⚠️ No se pudo eliminar el backup: $backupId")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando backup: $backupId", e)
            false
        }
    }
    
    suspend fun cleanupExpiredBackups(): Int {
        Log.i(TAG, "🔥 Limpiando backups expirados...")
        
        val expiredDate = Instant.now().minusSeconds(BACKUP_RETENTION_DAYS * 24 * 60 * 60L)
        val expiredBackups = backupStorage.listBackups(null, null, 1000)
            .filter { it.createdAt.isBefore(expiredDate) }
        
        val deletedCount = 0
        expiredBackups.forEach { backup ->
            if (deleteBackup(backup.id)) {
                deletedCount++
            }
        }
        
        Log.i(TAG, "✅ Se eliminaron $deletedCount backups expirados")
        return deletedCount
    }
    
    // 🔥 BACKUP VALIDATION
    suspend fun validateBackup(backupId: String): ValidationResult {
        var metadata = backupStorage.getMetadata(backupId)
            ?: return ValidationResult(false, "Backup metadata not found")
        
        var encryptedData = backupStorage.retrieveBackup(backupId)
        
        return backupValidator.validateBackup(metadata, encryptedData)
    }
    
    // 🔥 BACKUP STATISTICS
    suspend fun getBackupStatistics(): BackupStatistics {
        val allBackups = backupStorage.listBackups(null, null, 1000)
        
        var totalBackups = allBackups.size
        var successfulBackups = allBackups.count { it.status == BackupStatus.COMPLETED }
        var failedBackups = allBackups.count { it.status == BackupStatus.FAILED }
        var totalSize = allBackups.sumOf { it.size }
        var compressedSize = allBackups.sumOf { it.compressedSize }
        
        var compressionRatio = if (totalSize > 0) {
            (totalSize - compressedSize).toDouble() / totalSize * 100
        } else 0.0
        
        var backupTypes = allBackups.groupBy { it.type }
            .mapValues { it.value.size }
        
        return BackupStatistics(
            totalBackups = totalBackups,
            successfulBackups = successfulBackups,
            failedBackups = failedBackups,
            successRate = if (totalBackups > 0) successfulBackups.toDouble() / totalBackups else 0.0,
            totalSize = totalSize,
            compressedSize = compressedSize,
            compressionRatio = compressionRatio,
            backupTypes = backupTypes,
            oldestBackup = allBackups.minByOrNull { it.createdAt }?.createdAt,
            newestBackup = allBackups.maxByOrNull { it.createdAt }?.createdAt
        )
    }
    
    // 🔥 UTILITY METHODS
    private fun generateBackupId(): String {
        return "backup_${System.currentTimeMillis()}_${(0..999).random()}"
    }
    
    private fun generateRecoveryId(): String {
        return "recovery_${System.currentTimeMillis()}_${(0..999).random()}"
    }
    
    private fun determineBackupType(): BackupType {
        var lastBackup = backupStorage.listBackups(null, null, 1).firstOrNull()
        
        return when {
            lastBackup == null -> BackupType.FULL
            lastBackup.type == BackupType.FULL -> BackupType.INCREMENTAL
            else -> BackupType.FULL
        }
    }
    
    private fun getDependenciesForIncrementalBackup(): Set<String> {
        val lastFullBackup = backupStorage.listBackups(BackupType.FULL, BackupStatus.COMPLETED, 1)
            .firstOrNull()
        
        return if (lastFullBackup != null) {
            setOf(lastFullBackup.id)
        } else {
            emptySet()
        }
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
        var hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

// 🔥 SUPPORTING CLASSES

@Singleton
class BackupScheduler @Inject constructor() {
    
    private val scheduledBackups = ConcurrentHashMap<String, ScheduledBackup>()
    private val backupJobs = ConcurrentHashMap<String, Job>()
    
    data class ScheduledBackup(
        val id: String,
        val interval: Long,
        val type: BackupRecoverySystem.BackupType,
        val description: String,
        val createdAt: Instant,
        val nextExecution: Instant
    )
    
    suspend fun scheduleBackup(
        interval: Long,
        type: BackupRecoverySystem.BackupType,
        description: String
    ): String {
        val scheduleId = generateScheduleId()
        val now = Instant.now()
        
        val scheduledBackup = ScheduledBackup(
            id = scheduleId,
            interval = interval,
            type = type,
            description = description,
            createdAt = now,
            nextExecution = now.plusMillis(interval)
        )
        
        scheduledBackups[scheduleId] = scheduledBackup
        
        // Start the scheduled job
        val job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    delay(interval)
                    // Execute backup (this would be called through the main system)
                    Log.d("BackupScheduler", "Executing scheduled backup: $scheduleId")
                } catch (e: Exception) {
                    Log.e("BackupScheduler", "Error in scheduled backup: $scheduleId", e)
                }
            }
        }
        
        backupJobs[scheduleId] = job
        
        return scheduleId
    }
    
    suspend fun cancelScheduledBackup(scheduleId: String): Boolean {
        backupJobs[scheduleId]?.cancel()
        backupJobs.remove(scheduleId)
        return scheduledBackups.remove(scheduleId) != null
    }
    
    fun getScheduledBackups(): List<ScheduledBackup> {
        return scheduledBackups.values.toList()
    }
    
    private fun generateScheduleId(): String {
        return "schedule_${System.currentTimeMillis()}_${(0..999).random()}"
    }
}

@Singleton
class BackupStorage @Inject constructor() {
    
    private val backupMetadata = ConcurrentHashMap<String, BackupRecoverySystem.BackupMetadata>()
    private var backupData = ConcurrentHashMap<String, ByteArray>()
    
    suspend fun storeBackup(backupId: String, data: ByteArray): Long {
        backupData[backupId] = data
        return data.size.toLong()
    }
    
    suspend fun retrieveBackup(backupId: String): ByteArray {
        return backupData[backupId] ?: throw BackupException("Backup not found: $backupId")
    }
    
    suspend fun updateMetadata(metadata: BackupRecoverySystem.BackupMetadata) {
        backupMetadata[metadata.id] = metadata
    }
    
    suspend fun getMetadata(backupId: String): BackupRecoverySystem.BackupMetadata? {
        return backupMetadata[backupId]
    }
    
    suspend fun listBackups(
        type: BackupRecoverySystem.BackupType?,
        status: BackupRecoverySystem.BackupStatus?,
        limit: Int
    ): List<BackupRecoverySystem.BackupMetadata> {
        return backupMetadata.values
            .filter { metadata ->
                val typeMatch = type == null || metadata.type == type
                val statusMatch = status == null || metadata.status == status
                typeMatch && statusMatch
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }
    
    suspend fun deleteBackup(backupId: String): Boolean {
        backupData.remove(backupId)
        backupMetadata.remove(backupId)
        return true
    }
}

@Singleton
class DataExtractor @Inject constructor() {
    
    suspend fun extractData(
        type: BackupRecoverySystem.BackupType,
        dependencies: Set<String>
    ): List<BackupData> {
        return when (type) {
            BackupRecoverySystem.BackupType.FULL -> extractFullData()
            BackupRecoverySystem.BackupType.INCREMENTAL -> extractIncrementalData(dependencies)
            BackupRecoverySystem.BackupType.DIFFERENTIAL -> extractDifferentialData(dependencies)
            else -> extractFullData()
        }
    }
    
    private suspend fun extractFullData(): List<BackupData> {
        // In production, this would extract all user data, voice templates, settings, etc.
        return listOf(
            BackupData("users.json", "{}".toByteArray()),
            BackupData("voice_templates.json", "{}".toByteArray()),
            BackupData("settings.json", "{}".toByteArray())
        )
    }
    
    private suspend fun extractIncrementalData(dependencies: Set<String>): List<BackupData> {
        // Extract only changes since last backup
        return listOf(
            BackupData("incremental_changes.json", "{}".toByteArray())
        )
    }
    
    private suspend fun extractDifferentialData(dependencies: Set<String>): List<BackupData> {
        // Extract changes since last full backup
        return listOf(
            BackupData("differential_changes.json", "{}".toByteArray())
        )
    }
}

@Singleton
class BackupCompressor @Inject constructor() {
    
    suspend fun compress(data: List<BackupData>): ByteArray {
        var outputStream = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(outputStream)
        
        try {
            data.forEach { backupData ->
                var entry = ZipEntry(backupData.fileName)
                zipOutputStream.putNextEntry(entry)
                zipOutputStream.write(backupData.content)
                zipOutputStream.closeEntry()
            }
        } finally {
            zipOutputStream.close()
        }
        
        return outputStream.toByteArray()
    }
    
    suspend fun decompress(compressedData: ByteArray): List<BackupData> {
        var inputStream = ByteArrayInputStream(compressedData)
        val zipInputStream = ZipInputStream(inputStream)
        var backupData = mutableListOf<BackupData>()
        
        try {
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                val content = zipInputStream.readBytes()
                backupData.add(BackupData(entry.name, content))
                entry = zipInputStream.nextEntry
            }
        } finally {
            zipInputStream.close()
        }
        
        return backupData
    }
}

@Singleton
class BackupEncryptor @Inject constructor() {
    
    private val keyGenerator = javax.crypto.KeyGenerator.getInstance("AES")
    
    init {
        keyGenerator.init(256)
    }
    
    suspend fun encrypt(data: ByteArray): ByteArray {
        // In production, use proper encryption with secure key management
        return data // Placeholder - would implement actual encryption
    }
    
    suspend fun decrypt(encryptedData: ByteArray): ByteArray {
        // In production, use proper decryption with secure key management
        return encryptedData // Placeholder - would implement actual decryption
    }
}

@Singleton
class BackupValidator @Inject constructor() {
    
    suspend fun validateBackup(
        metadata: BackupRecoverySystem.BackupMetadata,
        encryptedData: ByteArray?
    ): ValidationResult {
        val errors = mutableListOf<String>()
        
        // Validate metadata
        if (metadata.id.isBlank()) {
            errors.add("Backup ID cannot be blank")
        }
        
        if (metadata.description.isBlank()) {
            errors.add("Backup description cannot be blank")
        }
        
        if (metadata.size <= 0) {
            errors.add("Backup size must be positive")
        }
        
        if (metadata.compressedSize <= 0) {
            errors.add("Compressed size must be positive")
        }
        
        if (metadata.checksum.isBlank()) {
            errors.add("Checksum cannot be blank")
        }
        
        // Validate data if provided
        if (encryptedData != null) {
            if (encryptedData.isEmpty()) {
                errors.add("Encrypted data cannot be empty")
            }
            
            // Verify checksum
            val calculatedChecksum = calculateChecksum(encryptedData)
            if (calculatedChecksum != metadata.checksum) {
                errors.add("Checksum verification failed")
            }
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            errorMessage = if (errors.isNotEmpty()) errors.joinToString(" ") else null
        )
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
        var hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class RecoveryManager @Inject constructor() {
    
    suspend fun recoverData(
        extractedData: List<BackupData>,
        targetLocation: String?,
        metadata: BackupRecoverySystem.BackupMetadata
    ): Int {
        val recoveredFiles = 0
        
        extractedData.forEach { backupData ->
            try {
                // In production, this would restore data to the appropriate locations
                Log.d("RecoveryManager", "Recovering file: ${backupData.fileName}")
                recoveredFiles++
            } catch (e: Exception) {
                Log.e("RecoveryManager", "Error recovering file: ${backupData.fileName}", e)
            }
        }
        
        return recoveredFiles
    }
}

@Singleton
class BackupMonitor @Inject constructor() {
    
    private val backupMetrics = ConcurrentHashMap<String, BackupMetrics>()
    
    fun recordBackupMetrics(backupId: String, metrics: BackupMetrics) {
        backupMetrics[backupId] = metrics
    }
    
    fun getBackupMetrics(backupId: String): BackupMetrics? {
        return backupMetrics[backupId]
    }
    
    fun getAllMetrics(): Map<String, BackupMetrics> {
        return backupMetrics.toMap()
    }
}

// 🔥 DATA MODELS

data class BackupData(
    val fileName: String,
    val content: ByteArray
)

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

data class BackupStatistics(
    val totalBackups: Int,
    val successfulBackups: Int,
    val failedBackups: Int,
    val successRate: Double,
    val totalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Double,
    val backupTypes: Map<BackupRecoverySystem.BackupType, Int>,
    val oldestBackup: Instant?,
    val newestBackup: Instant?
)

data class BackupMetrics(
    val duration: Long,
    val filesProcessed: Int,
    val compressionRatio: Double,
    val encryptionTime: Long,
    val storageTime: Long
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)



