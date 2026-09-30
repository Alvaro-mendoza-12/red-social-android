plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

import java.io.FileInputStream
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

fun Properties.getNonBlank(key: String): String? = getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
// Password whitespace belongs to the secret, not to configuration formatting.
fun Properties.getSecret(key: String): String? = getProperty(key)?.takeIf { it.isNotEmpty() }
fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
fun String.isEphemeralBackendOrigin(): Boolean = runCatching {
    val host = URI(trim()).host.orEmpty().lowercase()
    host.endsWith(".trycloudflare.com") ||
        host.endsWith(".ngrok-free.app") ||
        host.endsWith(".ngrok.io")
}.getOrDefault(false)
fun String.isStableHttpsBackendOrigin(): Boolean = runCatching {
    val uri = URI(trim())
    val host = uri.host.orEmpty().lowercase()
    uri.scheme.equals("https", ignoreCase = true) &&
        host.contains('.') &&
        host != "localhost" &&
        !host.endsWith(".local") &&
        !host.matches(Regex("[0-9.]+")) &&
        uri.userInfo == null &&
        !isEphemeralBackendOrigin()
}.getOrDefault(false)
fun String.isDnsDiscoveryHost(): Boolean = matches(
    Regex("(?i)^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
)
fun String.isEcSpkiPublicKey(): Boolean = runCatching {
    val decoded = Base64.getDecoder().decode(trim())
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoded)).algorithm == "EC"
}.getOrDefault(false)

val buildCommit = providers.environmentVariable("NOVVIN_BUILD_COMMIT").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0 && value.matches(Regex("[0-9a-fA-F]{7,40}"))) value else "unknown"
    }.getOrDefault("unknown")

fun environmentNonBlank(key: String): String? = providers.environmentVariable(key).orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val releaseStoreFileValue = keystoreProperties.getNonBlank("storeFile")
    ?: environmentNonBlank("NOVVIN_UPLOAD_STORE_FILE")
val releaseStorePassword = keystoreProperties.getSecret("storePassword")
    ?: providers.environmentVariable("NOVVIN_UPLOAD_STORE_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
val releaseKeyAlias = keystoreProperties.getNonBlank("keyAlias")
    ?: environmentNonBlank("NOVVIN_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = keystoreProperties.getSecret("keyPassword")
    ?: providers.environmentVariable("NOVVIN_UPLOAD_KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
val isAuditBuild = environmentNonBlank("NOVVIN_AUDIT_BUILD")
    ?.equals("true", ignoreCase = true) == true
val releaseStoreFile = releaseStoreFileValue?.let { rootProject.file(it) }
val hasReleaseSigning =
    releaseStoreFile != null &&
        releaseStoreFile.isFile && releaseStoreFile.canRead() &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "conectate.com"
    compileSdk = 36

    defaultConfig {
        applicationId = "conectate.com"
        minSdk = 24
        targetSdk = 36
        versionCode = 116
        versionName = "9.0.19"
        buildConfigField("String", "BUILD_COMMIT", buildCommit.asBuildConfigString())

        val props = Properties()
        val secretsFile = rootProject.file("secrets.properties")
        if (secretsFile.exists()) {
            FileInputStream(secretsFile).use(props::load)
            val fallbackApiUrl = props.getNonBlank("FALLBACK_API_URL")
                ?: props.getNonBlank("LOCAL_FALLBACK_API_URL")
                ?: throw GradleException(
                    "FALLBACK_API_URL or LOCAL_FALLBACK_API_URL is required in secrets.properties for Ubuntu backend builds."
                )
            val signalingUrl = props.getNonBlank("SIGNALING_URL")
                ?: props.getNonBlank("VOICE_SIGNALING_URL")
                ?: ""
            val backendConfigUrl = props.getNonBlank("BACKEND_CONFIG_URL")
                ?: ""
            val backendDiscoveryHost = props.getNonBlank("BACKEND_DISCOVERY_HOST")
                ?: "conectate-alvaro.duckdns.org"
            val backendDiscoveryPublicKey = props.getNonBlank("BACKEND_DISCOVERY_PUBLIC_KEY")
                ?: rootProject.file("config/backend-discovery-public-key.txt")
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                ?: ""
            if (backendDiscoveryPublicKey.isNotBlank() && !backendDiscoveryPublicKey.isEcSpkiPublicKey()) {
                throw GradleException(
                    "BACKEND_DISCOVERY_PUBLIC_KEY must be an EC SPKI DER public key encoded as Base64."
                )
            }
            if (fallbackApiUrl.isEphemeralBackendOrigin() &&
                (!backendDiscoveryHost.isDnsDiscoveryHost() || backendDiscoveryPublicKey.isBlank())
            ) {
                throw GradleException(
                    "Ephemeral FALLBACK_API_URL builds require BACKEND_DISCOVERY_HOST and a valid " +
                        "BACKEND_DISCOVERY_PUBLIC_KEY so installed clients can recover after tunnel rotation."
                )
            }
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("String", "FALLBACK_API_URL", fallbackApiUrl.asBuildConfigString())
            buildConfigField("String", "SIGNALING_URL", signalingUrl.asBuildConfigString())
            buildConfigField("String", "BACKEND_CONFIG_URL", backendConfigUrl.asBuildConfigString())
            buildConfigField("String", "BACKEND_DISCOVERY_HOST", backendDiscoveryHost.asBuildConfigString())
            buildConfigField("String", "BACKEND_DISCOVERY_PUBLIC_KEY", backendDiscoveryPublicKey.asBuildConfigString())
        } else {
            throw GradleException(
                "secrets.properties is required and must define FALLBACK_API_URL or LOCAL_FALLBACK_API_URL for Ubuntu backend builds."
            )
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Permite ejecutar validaciones físicas muy dirigidas sin esconder ni
    // modificar la suite androidTest heredada, que se mantiene independiente.
    sourceSets {
        if (providers.gradleProperty("communicationValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/communicationValidationTest/java"))
        }
        if (providers.gradleProperty("audioValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(
                listOf("src/audioValidationTest/java")
            )
        }
    }

    splits {
        abi {
            // Play App Bundle handles ABI splits; local APK splits stay disabled for release bundles.
            isEnable = false
        }
    }

    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = false }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystores/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            versionNameSuffix = "-debug"
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"development\"")
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"production\"")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/gradle/incremental.annotation.processors",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/main/java"))
            res.setSrcDirs(listOf("src/main/res"))
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

tasks.register("qualityGateDebug") {
    group = "verification"
    description = "Runs the local quality gate (unit tests + lint) for debug."
    dependsOn("testDebugUnitTest", "lintDebug")
}

val validateStableReleaseBackend = tasks.register("validateStableReleaseBackend") {
    group = "verification"
    description = "Rejects temporary public API origins in distributable release builds."
    doLast {
        if (isAuditBuild) return@doLast
        val file = rootProject.file("secrets.properties")
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        val origin = properties.getNonBlank("FALLBACK_API_URL")
            ?: properties.getNonBlank("LOCAL_FALLBACK_API_URL")
            ?: throw GradleException("A stable FALLBACK_API_URL is required for release.")
        if (!origin.isStableHttpsBackendOrigin()) {
            throw GradleException(
                "Release builds require a stable HTTPS backend origin; temporary tunnel URLs are for audit builds only."
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateStableReleaseBackend)
}

// AGP consumes Android Studio's injected signing options itself. The custom
// guard must recognize that source without copying or overriding its secrets.
extra["novvinConfiguredReleaseSigning"] = hasReleaseSigning
apply(from = rootProject.file("gradle/release-signing-validation.gradle.kts"))

tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach {
    onlyIf("Crashlytics mapping uploads are disabled for explicit audit builds") {
        !isAuditBuild
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.google.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    // Legacy compatibility while remaining repositories are moved to the Ubuntu API.
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.gotrue)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer.hls)

    implementation(libs.accompanist.permissions)

    implementation(libs.mlkit.segmentation.selfie)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    implementation(libs.webrtc)
    implementation(libs.livekit.android)
    implementation(libs.socket.io.client) {
        exclude(group = "org.json", module = "json")
    }

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.multiplatform.settings)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.jsoup)
    implementation(libs.google.play.billing)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
