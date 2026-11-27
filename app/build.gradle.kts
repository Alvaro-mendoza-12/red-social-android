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


import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

/** Keep only the app ABI used across the separate instrumentation APK boundary. */
abstract class TraceQaInstrumentationReferences @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath abstract val targetJars: ListProperty<RegularFile>
    @get:Classpath abstract val targetDirectories: ListProperty<Directory>
    @get:Classpath abstract val sourceJars: ListProperty<RegularFile>
    @get:Classpath abstract val sourceDirectories: ListProperty<Directory>
    @get:Classpath abstract val bootClasspath: ConfigurableFileCollection
    @get:Classpath abstract val traceTool: ConfigurableFileCollection
    @get:OutputFile abstract val rules: RegularFileProperty

    @TaskAction fun trace() {
        val targets = targetJars.get().map { it.asFile } + targetDirectories.get().map { it.asFile }
        fun visitClasses(file: File, visit: (String, () -> ByteArray) -> Unit) {
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                    visit(it.relativeTo(file).invariantSeparatorsPath) { it.readBytes() }
                }
            } else ZipFile(file).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                    visit(entry.name) { zip.getInputStream(entry).use { it.readBytes() } }
                }
            }
        }
        val targetNames = mutableSetOf<String>()
        targets.forEach { visitClasses(it) { name, _ -> targetNames.add(name) } }
        val targetArchive = temporaryDir.resolve("app-classes.jar")
        val packedTargets = mutableSetOf<String>()
        ZipOutputStream(targetArchive.outputStream().buffered()).use { zip ->
            targets.forEach { visitClasses(it) { name, bytes ->
                if (packedTargets.add(name)) {
                    zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                    zip.write(bytes())
                    zip.closeEntry()
                }
            } }
        }
        // Scope.ALL may also expose the tested app. Remove shared definitions
        // from the sources, so they are traced as targets rather than duplicates.
        val sourceArchive = temporaryDir.resolve("instrumentation-classes.jar")
        val seen = mutableSetOf<String>()
        ZipOutputStream(sourceArchive.outputStream().buffered()).use { zip ->
            (sourceJars.get().map { it.asFile } + sourceDirectories.get().map { it.asFile }).forEach {
                visitClasses(it) { name, bytes ->
                    if (name !in targetNames && seen.add(name)) {
                        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                        zip.write(bytes())
                        zip.closeEntry()
                    }
                }
            }
        }
        check(seen.isNotEmpty()) { "No instrumentation classes to trace" }
        val output = rules.get().asFile.apply { parentFile.mkdirs() }
        val diagnostics = ByteArrayOutputStream()
        execOperations.javaexec {
            classpath = traceTool
            mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
            maxHeapSize = "2g"
            // Keep this cross-APK ABI stable, including Kotlin default-method
            // helpers synthesized after tracing. Applying only a name mapping
            // to the test APK cannot repair every synthesized helper call.
            args("--keep-rules", "--source", sourceArchive.absolutePath)
            // The Android SDK stubs omit hidden APIs and optional desktop/mock
            // adapters in the test runner. Review those diagnostics below;
            // unresolved application/test APIs must still fail this task.
            args("--map-diagnostics:MissingDefinitionsDiagnostic", "error", "warning")
            errorOutput = diagnostics
            bootClasspath.files.sortedBy { it.path }.forEach { args("--lib", it.absolutePath) }
            args("--target", targetArchive.absolutePath)
            args("--output", output.absolutePath)
        }.assertNormalExitValue()
        val diagnosticText = diagnostics.toString(Charsets.UTF_8)
        temporaryDir.resolve("trace-diagnostics.txt").writeText(diagnosticText)
        val sdkOrOptionalApis = listOf(
            "android.test.AndroidTestCase", "android.test.InstrumentationTestCase",
            "androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity",
            "java.beans.", "java.lang.invoke.StringConcatFactory",
            "javax.lang.model.element.Modifier", "org.easymock.", "org.jmock.core.Constraint", "sun.misc.Unsafe",
        )
        val missing = diagnosticText.lineSequence().map { it.removePrefix("Warning: ") }
            .filter { it.startsWith("Missing ") }.toList()
        val unexpected = missing.filter { line ->
            val declaration = line.substringBefore(" (referenced from:")
            val symbol = if (declaration.startsWith("Missing class ")) declaration.removePrefix("Missing class ")
                else declaration.removePrefix("Missing method ").removePrefix("Missing field ").substringAfter(' ')
            sdkOrOptionalApis.none { symbol.startsWith(it) }
        }
        check(unexpected.isEmpty()) { "Unresolved instrumentation references:\n${unexpected.joinToString("\n")}" }
        if (missing.isNotEmpty()) logger.lifecycle("QA tracing: {} SDK/optional adapter diagnostics; see {}",
            missing.size, temporaryDir.resolve("trace-diagnostics.txt"))
        check(output.isFile && output.length() > 0) { "Missing instrumentation keep rules" }
    }
}

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
        !host.endsWith('.') &&
        host.matches(Regex("^[a-z0-9.-]+$")) &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
        (uri.port in intArrayOf(-1, 443) || (uri.port == 8443 && host.endsWith(".ts.net"))) &&
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
val selectedNdkVersion = providers.gradleProperty("novvinNdkVersion").orNull
    ?.trim()?.takeIf(String::isNotEmpty)

android {
    namespace = "conectate.com"
    compileSdk = 36
    selectedNdkVersion?.let { ndkVersion = it }

    defaultConfig {
        applicationId = "conectate.com"
        minSdk = 24
        targetSdk = 36
        versionCode = 191
        versionName = "9.2.1"
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
            val mediaEdgeOrigin = environmentNonBlank("NOVVIN_MEDIA_EDGE_ORIGIN")
                ?: props.getNonBlank("MEDIA_EDGE_ORIGIN")
                ?: ""
            buildConfigField("String", "MEDIA_EDGE_ORIGIN", mediaEdgeOrigin.asBuildConfigString())
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
        if (providers.gradleProperty("experienceValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/experienceValidationTest/java", "src/badgeValidationTest/java"))
        }
        if (providers.gradleProperty("badgeValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/badgeValidationTest/java"))
        }
        if (providers.gradleProperty("communicationValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(listOf("src/communicationValidationTest/java"))
            getByName("androidTest").manifest.srcFile("src/communicationValidationTest/AndroidManifest.xml")
        }
        if (providers.gradleProperty("audioValidationOnly").orNull == "true") {
            getByName("androidTest").java.setSrcDirs(
                listOf("src/audioValidationTest/java")
            )
        }
    }

    splits {
        abi {
            // Opt in for standalone sideload APKs. The AAB continues to split
            // by device configuration in Play.
            isEnable = providers.gradleProperty("novvinAbiApks").orNull == "true"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
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

        // Keep Play installations and their private data intact during device QA.
        create("audit") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "Novvin QA")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            if (selectedNdkVersion != null) ndk.debugSymbolLevel = "SYMBOL_TABLE"
            buildConfigField("String", "BUILD_ENVIRONMENT", "\"production\"")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // Same optimizer as release, installed separately for repeatable startup QA.
        create("auditRelease") {
            initWith(getByName("release"))
            // Instrumentation shares runtime dependencies with the tested APK.
            // Retain its JUnit runtime only in QA while keeping app optimization.
            proguardFiles("proguard-audit.pro")
            // The handset audit may preserve its existing account and database.
            // The default remains an independently installed QA application.
            if (providers.gradleProperty("startupAuditPrimaryPackage").orNull != "true") {
                applicationIdSuffix = ".qa"
            }
            versionNameSuffix = "-qa-optimized"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "Novvin QA")
            // QA has no Firebase client; its skipped Google Services generator
            // cannot supply the resources required by a mapping upload.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }

    // Run device regressions in the existing isolated QA package when requested,
    // so a tablet's Play installation and private messages are preserved.
    testBuildType = providers.gradleProperty("novvinInstrumentationBuildType").orElse("debug").get()

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
        listOf("debug", "audit", "auditRelease").forEach { variant ->
            getByName(variant).java.srcDir("src/auditSupport/java")
        }
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/main/java"))
            res.setSrcDirs(listOf("src/main/res"))
            assets.srcDir(rootProject.file("NoWing-web/public/board-games"))
            assets.srcDir(rootProject.file("infrastructure/local-fallback/api/src/board-games/engine"))
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        htmlReport = true
        xmlReport = true
    }
}

// The isolated QA application has no Firebase client registration. Keep the
// generator as a completed/skipped task (rather than excluding it, which breaks
// AGP's generated-resource provider), and leave release/debug Firebase intact.
tasks.matching {
    it.name == "processAuditGoogleServices" || it.name == "processAuditReleaseGoogleServices"
}.configureEach { enabled = false }

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
    onlyIf("No external Crashlytics uploads from isolated audit or CI fixture builds") {
        !isAuditBuild && providers.environmentVariable("NOVVIN_CI_FIXTURE").orNull != "true"
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("auditRelease")) { variant ->
    variant.androidTest?.let { instrumentation ->
        val trace = tasks.register<TraceQaInstrumentationReferences>("traceAuditReleaseInstrumentationReferences") {
            bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
            // Use the exact R8 distribution bundled with the active AGP.
            traceTool.from(File(Class.forName("com.android.tools.r8.tracereferences.TraceReferences")
                .protectionDomain.codeSource.location.toURI()))
            rules.set(layout.buildDirectory.file("intermediates/qa_instrumentation/keep-rules.pro"))
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(trace).toGet(
            ScopedArtifact.CLASSES,
            TraceQaInstrumentationReferences::targetJars,
            TraceQaInstrumentationReferences::targetDirectories,
        )
        instrumentation.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(trace).toGet(
            ScopedArtifact.CLASSES,
            TraceQaInstrumentationReferences::sourceJars,
            TraceQaInstrumentationReferences::sourceDirectories,
        )
        variant.proguardFiles.add(trace.flatMap { it.rules })
    }
}


dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("CVE-2026-102554: use the corrected Android library")
        }
        implementation("com.google.protobuf:protobuf-javalite:3.25.9") {
            because("CVE-2024-7254: bound recursion when parsing unknown Protobuf fields")
        }
        implementation(libs.multiplatform.settings) {
            because("Keep the resolved version for legacy authentication while Supabase still uses it")
        }
    }

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
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.video)

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
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.jsoup)
    implementation(libs.google.play.billing)
    implementation(libs.google.play.app.update)
    implementation(libs.androidx.exifinterface)

    testImplementation("com.google.protobuf:protobuf-javalite:3.25.9")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation("io.mockk:mockk-jvm:1.14.12")
    testImplementation("io.ktor:ktor-client-mock:2.3.13")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("auditImplementation", libs.androidx.compose.ui.test.manifest)
    add("auditReleaseImplementation", libs.androidx.compose.ui.test.manifest)
}
