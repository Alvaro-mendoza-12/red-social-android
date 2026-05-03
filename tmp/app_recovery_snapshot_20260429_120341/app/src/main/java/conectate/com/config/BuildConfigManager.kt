package conectate.com.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔥 BUILD CONFIG MANAGER - CONFIGURACIÓN DE BUILD
 * Gestión de configuración específica de build y variantes
 * 
 * Este sistema maneja:
 * - Build variants (debug, release)
 * - Build types
 * - Product flavors
 * - Build configurations
 */

@Singleton
class BuildConfigManager @Inject constructor() {
    
    // 🔥 BUILD TYPES
    enum class BuildType {
        DEBUG,
        RELEASE,
        BENCHMARK
    }
    
    // 🔥 PRODUCT FLAVORS
    enum class ProductFlavor {
        DEV,
        STAGING,
        PROD
    }
    
    // 🔥 CURRENT BUILD CONFIGURATION
    val currentBuildType: BuildType = detectBuildType()
    val currentProductFlavor: ProductFlavor = detectProductFlavor()
    val isDebugBuild: Boolean = currentBuildType == BuildType.DEBUG
    val isReleaseBuild: Boolean = currentBuildType == BuildType.RELEASE
    
    private fun detectBuildType(): BuildType {
        return when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            BuildConfig.BUILD_TYPE == "benchmark" -> BuildType.BENCHMARK
            else -> BuildType.RELEASE
        }
    }
    
    private fun detectProductFlavor(): ProductFlavor {
        return when (BuildConfig.FLAVOR) {
            "staging" -> ProductFlavor.STAGING
            "prod" -> ProductFlavor.PROD
            else -> ProductFlavor.DEV
        }
    }
    
    // 🔥 BUILD CONFIGURATION DATA
    data class BuildConfiguration(
        val buildType: BuildType,
        val productFlavor: ProductFlavor,
        val versionCode: Int,
        val versionName: String,
        val applicationId: String,
        val debuggable: Boolean,
        val minifyEnabled: Boolean,
        val shrinkResourcesEnabled: Boolean,
        val enableProfiling: Boolean,
        val enableCrashlytics: Boolean,
        val enableAnalytics: Boolean
    )
    
    val buildConfiguration: BuildConfiguration
        get() = BuildConfiguration(
            buildType = currentBuildType,
            productFlavor = currentProductFlavor,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            debuggable = isDebugBuild,
            minifyEnabled = !isDebugBuild,
            shrinkResourcesEnabled = !isDebugBuild,
            enableProfiling = isDebugBuild || currentProductFlavor == ProductFlavor.STAGING,
            enableCrashlytics = isReleaseBuild,
            enableAnalytics = isReleaseBuild || currentProductFlavor == ProductFlavor.STAGING
        )
    
    // 🔥 ENDPOINT CONFIGURATION
    data class EndpointConfiguration(
        val baseUrl: String,
        val apiUrl: String,
        val wsUrl: String,
        val cdnUrl: String,
        val analyticsUrl: String,
        val crashlyticsUrl: String
    )
    
    val endpointConfiguration: EndpointConfiguration
        get() = when (currentProductFlavor) {
            ProductFlavor.DEV -> EndpointConfiguration(
                baseUrl = "https://dev-api.conectate.com",
                apiUrl = "https://dev-api.conectate.com/v1",
                wsUrl = "wss://dev-ws.conectate.com",
                cdnUrl = "https://dev-cdn.conectate.com",
                analyticsUrl = "https://dev-analytics.conectate.com",
                crashlyticsUrl = "https://dev-crashlytics.conectate.com"
            )
            ProductFlavor.STAGING -> EndpointConfiguration(
                baseUrl = "https://staging-api.conectate.com",
                apiUrl = "https://staging-api.conectate.com/v1",
                wsUrl = "wss://staging-ws.conectate.com",
                cdnUrl = "https://staging-cdn.conectate.com",
                analyticsUrl = "https://staging-analytics.conectate.com",
                crashlyticsUrl = "https://staging-crashlytics.conectate.com"
            )
            ProductFlavor.PROD -> EndpointConfiguration(
                baseUrl = "https://api.conectate.com",
                apiUrl = "https://api.conectate.com/v1",
                wsUrl = "wss://ws.conectate.com",
                cdnUrl = "https://cdn.conectate.com",
                analyticsUrl = "https://analytics.conectate.com",
                crashlyticsUrl = "https://crashlytics.conectate.com"
            )
        }
    
    // 🔥 LOGGING CONFIGURATION
    data class LoggingConfiguration(
        val enableVerboseLogging: Boolean,
        val enableDebugLogging: Boolean,
        val enableInfoLogging: Boolean,
        val enableWarningLogging: Boolean,
        val enableErrorLogging: Boolean,
        val enableNetworkLogging: Boolean,
        val enableDatabaseLogging: Boolean,
        val enablePerformanceLogging: Boolean,
        val logToFile: Boolean,
        val logToConsole: Boolean,
        val logToRemote: Boolean
    )
    
    val loggingConfiguration: LoggingConfiguration
        get() = when (currentBuildType) {
            BuildType.DEBUG -> LoggingConfiguration(
                enableVerboseLogging = true,
                enableDebugLogging = true,
                enableInfoLogging = true,
                enableWarningLogging = true,
                enableErrorLogging = true,
                enableNetworkLogging = true,
                enableDatabaseLogging = true,
                enablePerformanceLogging = true,
                logToFile = true,
                logToConsole = true,
                logToRemote = false
            )
            BuildType.BENCHMARK -> LoggingConfiguration(
                enableVerboseLogging = false,
                enableDebugLogging = false,
                enableInfoLogging = true,
                enableWarningLogging = true,
                enableErrorLogging = true,
                enableNetworkLogging = false,
                enableDatabaseLogging = true,
                enablePerformanceLogging = true,
                logToFile = true,
                logToConsole = false,
                logToRemote = false
            )
            BuildType.RELEASE -> LoggingConfiguration(
                enableVerboseLogging = false,
                enableDebugLogging = false,
                enableInfoLogging = false,
                enableWarningLogging = true,
                enableErrorLogging = true,
                enableNetworkLogging = false,
                enableDatabaseLogging = false,
                enablePerformanceLogging = false,
                logToFile = true,
                logToConsole = false,
                logToRemote = true
            )
        }
    
    // 🔥 FEATURE FLAGS BY BUILD
    data class BuildFeatureFlags(
        val enableDebugMenu: Boolean,
        val enableDevTools: Boolean,
        val enableStetho: Boolean,
        val enableFlipper: Boolean,
        val enableLeakCanary: Boolean,
        val enableStrictMode: Boolean,
        val enableDebugLayout: Boolean,
        val enableNetworkInspector: Boolean,
        val enableDatabaseInspector: Boolean,
        val enablePerformanceProfiler: Boolean
    )
    
    val buildFeatureFlags: BuildFeatureFlags
        get() = when (currentBuildType) {
            BuildType.DEBUG -> BuildFeatureFlags(
                enableDebugMenu = true,
                enableDevTools = true,
                enableStetho = true,
                enableFlipper = true,
                enableLeakCanary = true,
                enableStrictMode = true,
                enableDebugLayout = true,
                enableNetworkInspector = true,
                enableDatabaseInspector = true,
                enablePerformanceProfiler = true
            )
            BuildType.BENCHMARK -> BuildFeatureFlags(
                enableDebugMenu = false,
                enableDevTools = false,
                enableStetho = false,
                enableFlipper = false,
                enableLeakCanary = false,
                enableStrictMode = false,
                enableDebugLayout = false,
                enableNetworkInspector = false,
                enableDatabaseInspector = false,
                enablePerformanceProfiler = true
            )
            BuildType.RELEASE -> BuildFeatureFlags(
                enableDebugMenu = false,
                enableDevTools = false,
                enableStetho = false,
                enableFlipper = false,
                enableLeakCanary = false,
                enableStrictMode = false,
                enableDebugLayout = false,
                enableNetworkInspector = false,
                enableDatabaseInspector = false,
                enablePerformanceProfiler = false
            )
        }
    
    // 🔥 SECURITY CONFIGURATION BY BUILD
    data class BuildSecurityConfiguration(
        val enableSSLVerification: Boolean,
        val enableCertificatePinning: Boolean,
        val enableNetworkSecurityConfig: Boolean,
        val enableObfuscation: Boolean,
        val enableAntiDebug: Boolean,
        val enableRootDetection: Boolean,
        val enableEmulatorDetection: Boolean,
        val enableTamperDetection: Boolean
    )
    
    val buildSecurityConfiguration: BuildSecurityConfiguration
        get() = when (currentBuildType) {
            BuildType.DEBUG -> BuildSecurityConfiguration(
                enableSSLVerification = true,
                enableCertificatePinning = false,
                enableNetworkSecurityConfig = true,
                enableObfuscation = false,
                enableAntiDebug = false,
                enableRootDetection = false,
                enableEmulatorDetection = false,
                enableTamperDetection = false
            )
            BuildType.BENCHMARK -> BuildSecurityConfiguration(
                enableSSLVerification = true,
                enableCertificatePinning = true,
                enableNetworkSecurityConfig = true,
                enableObfuscation = true,
                enableAntiDebug = false,
                enableRootDetection = false,
                enableEmulatorDetection = false,
                enableTamperDetection = false
            )
            BuildType.RELEASE -> BuildSecurityConfiguration(
                enableSSLVerification = true,
                enableCertificatePinning = true,
                enableNetworkSecurityConfig = true,
                enableObfuscation = true,
                enableAntiDebug = true,
                enableRootDetection = true,
                enableEmulatorDetection = true,
                enableTamperDetection = true
            )
        }
    
    // 🔥 PERFORMANCE CONFIGURATION BY BUILD
    data class BuildPerformanceConfiguration(
        val enablePerformanceMonitoring: Boolean,
        val enableMemoryProfiling: Boolean,
        val enableCPUPprofiling: Boolean,
        val enableNetworkProfiling: Boolean,
        val enableDatabaseProfiling: Boolean,
        val enableRenderProfiling: Boolean,
        val enableStartupProfiling: Boolean,
        val enableFrameMetrics: Boolean
    )
    
    val buildPerformanceConfiguration: BuildPerformanceConfiguration
        get() = when (currentBuildType) {
            BuildType.DEBUG -> BuildPerformanceConfiguration(
                enablePerformanceMonitoring = true,
                enableMemoryProfiling = true,
                enableCPUPprofiling = true,
                enableNetworkProfiling = true,
                enableDatabaseProfiling = true,
                enableRenderProfiling = true,
                enableStartupProfiling = true,
                enableFrameMetrics = true
            )
            BuildType.BENCHMARK -> BuildPerformanceConfiguration(
                enablePerformanceMonitoring = true,
                enableMemoryProfiling = true,
                enableCPUPprofiling = true,
                enableNetworkProfiling = false,
                enableDatabaseProfiling = true,
                enableRenderProfiling = true,
                enableStartupProfiling = true,
                enableFrameMetrics = true
            )
            BuildType.RELEASE -> BuildPerformanceConfiguration(
                enablePerformanceMonitoring = false,
                enableMemoryProfiling = false,
                enableCPUPprofiling = false,
                enableNetworkProfiling = false,
                enableDatabaseProfiling = false,
                enableRenderProfiling = false,
                enableStartupProfiling = false,
                enableFrameMetrics = false
            )
        }
    
    // 🔥 VALIDATION
    fun validateBuildConfiguration(): BuildValidationResult {
        var issues = mutableListOf<String>()
        var warnings = mutableListOf<String>()
        
        // Validar configuración básica
        if (buildConfiguration.versionCode <= 0) {
            issues.add("Version code must be positive")
        }
        
        if (buildConfiguration.versionName.isBlank()) {
            issues.add("Version name cannot be blank")
        }
        
        if (buildConfiguration.applicationId.isBlank()) {
            issues.add("Application ID cannot be blank")
        }
        
        // Validar endpoints
        if (endpointConfiguration.baseUrl.isBlank()) {
            issues.add("Base URL cannot be blank")
        }
        
        if (!endpointConfiguration.baseUrl.startsWith("https://") && currentBuildType == BuildType.RELEASE) {
            issues.add("Production must use HTTPS")
        }
        
        // Validar configuración de seguridad
        if (!buildSecurityConfiguration.enableSSLVerification) {
            warnings.add("SSL verification is disabled")
        }
        
        if (currentBuildType == BuildType.RELEASE && !buildSecurityConfiguration.enableObfuscation) {
            warnings.add("Code obfuscation is disabled in release build")
        }
        
        return BuildValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
            buildType = currentBuildType.name,
            productFlavor = currentProductFlavor.name
        )
    }
    
    // 🔥 UTILITIES
    fun getBuildInfo(): BuildInfo {
        return BuildInfo(
            buildType = currentBuildType.name,
            productFlavor = currentProductFlavor.name,
            versionCode = buildConfiguration.versionCode,
            versionName = buildConfiguration.versionName,
            applicationId = buildConfiguration.applicationId,
            buildTime = System.currentTimeMillis(),
            debuggable = buildConfiguration.debuggable,
            gitCommit = getGitCommit(),
            gitBranch = getGitBranch(),
            buildNumber = getBuildNumber()
        )
    }
    
    private fun getGitCommit(): String {
        return try {
            // En implementación real, esto obtendría el commit actual
            "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun getGitBranch(): String {
        return try {
            // En implementación real, esto obtendría la rama actual
            "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun getBuildNumber(): String {
        return try {
            // En implementación real, esto obtendría el número de build
            "1"
        } catch (e: Exception) {
            "1"
        }
    }
    
    // 🔥 FLOW PROVIDERS
    fun getBuildConfigurationFlow(): Flow<BuildConfiguration> = flow {
        emit(buildConfiguration)
    }
    
    fun getEndpointConfigurationFlow(): Flow<EndpointConfiguration> = flow {
        emit(endpointConfiguration)
    }
    
    fun getLoggingConfigurationFlow(): Flow<LoggingConfiguration> = flow {
        emit(loggingConfiguration)
    }
    
    fun getBuildFeatureFlagsFlow(): Flow<BuildFeatureFlags> = flow {
        emit(buildFeatureFlags)
    }
    
    fun getBuildSecurityConfigurationFlow(): Flow<BuildSecurityConfiguration> = flow {
        emit(buildSecurityConfiguration)
    }
    
    fun getBuildPerformanceConfigurationFlow(): Flow<BuildPerformanceConfiguration> = flow {
        emit(buildPerformanceConfiguration)
    }
}

// 🔥 DATA MODELS

data class BuildValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val warnings: List<String>,
    val buildType: String,
    val productFlavor: String
)

data class BuildInfo(
    val buildType: String,
    val productFlavor: String,
    val versionCode: Int,
    val versionName: String,
    val applicationId: String,
    val buildTime: Long,
    val debuggable: Boolean,
    val gitCommit: String,
    val gitBranch: String,
    val buildNumber: String
)

// 🔥 BUILD CONFIG PROVIDER

@Singleton
class BuildConfigProvider @Inject constructor(
    private val buildConfigManager: BuildConfigManager
) {
    
    fun getBuildConfiguration(): BuildConfiguration {
        return buildConfigManager.buildConfiguration
    }
    
    fun getEndpointConfiguration(): EndpointConfiguration {
        return buildConfigManager.endpointConfiguration
    }
    
    fun getLoggingConfiguration(): LoggingConfiguration {
        return buildConfigManager.loggingConfiguration
    }
    
    fun getBuildFeatureFlags(): BuildFeatureFlags {
        return buildConfigManager.buildFeatureFlags
    }
    
    fun getBuildSecurityConfiguration(): BuildSecurityConfiguration {
        return buildConfigManager.buildSecurityConfiguration
    }
    
    fun getBuildPerformanceConfiguration(): BuildPerformanceConfiguration {
        return buildConfigManager.buildPerformanceConfiguration
    }
    
    fun validateBuildConfiguration(): BuildValidationResult {
        return buildConfigManager.validateBuildConfiguration()
    }
    
    fun getBuildInfo(): BuildInfo {
        return buildConfigManager.getBuildInfo()
    }
    
    fun isDebugBuild(): Boolean = buildConfigManager.isDebugBuild
    fun isReleaseBuild(): Boolean = buildConfigManager.isReleaseBuild
}



