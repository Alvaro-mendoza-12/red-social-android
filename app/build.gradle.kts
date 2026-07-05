plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

import java.io.FileInputStream
import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

fun Properties.getNonBlank(key: String): String? = getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseStoreFile = keystoreProperties.getNonBlank("storeFile")?.let { rootProject.file(it) }
val hasReleaseSigning =
    keystorePropertiesFile.exists() &&
        releaseStoreFile != null &&
        releaseStoreFile.exists() &&
        keystoreProperties.getNonBlank("storePassword") != null &&
        keystoreProperties.getNonBlank("keyAlias") != null &&
        keystoreProperties.getNonBlank("keyPassword") != null

android {
    namespace = "conectate.com"
    compileSdk = 35

    defaultConfig {
        applicationId = "conectate.com"
        minSdk = 24
        targetSdk = 35
        versionCode = 41
        versionName = "3.0.1"

        val props = Properties()
        val secretsFile = rootProject.file("secrets.properties")
        if (secretsFile.exists()) {
            FileInputStream(secretsFile).use(props::load)
            val upstashRestUrl = props.getProperty("UPSTASH_REST_URL") ?: ""
            val upstashToken = props.getProperty("UPSTASH_TOKEN") ?: ""
            val tmdbApiKey = props.getProperty("TMDB_API_KEY") ?: ""
            val realDebridToken = props.getProperty("REAL_DEBRID_TOKEN") ?: ""
            val fallbackApiUrl = props.getNonBlank("FALLBACK_API_URL")
                ?: props.getNonBlank("LOCAL_FALLBACK_API_URL")
                ?: throw GradleException(
                    "FALLBACK_API_URL or LOCAL_FALLBACK_API_URL is required in secrets.properties for Ubuntu backend builds."
                )
            val backendConfigUrl = props.getNonBlank("BACKEND_CONFIG_URL")
                ?: ""
            val backendDiscoveryHost = props.getNonBlank("BACKEND_DISCOVERY_HOST")
                ?: "conectate-alvaro.duckdns.org"
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            buildConfigField("String", "FALLBACK_API_URL", fallbackApiUrl.asBuildConfigString())
            buildConfigField("String", "BACKEND_CONFIG_URL", backendConfigUrl.asBuildConfigString())
            buildConfigField("String", "BACKEND_DISCOVERY_HOST", backendDiscoveryHost.asBuildConfigString())
            buildConfigField("String", "UPSTASH_REST_URL", upstashRestUrl.asBuildConfigString())
            buildConfigField("String", "UPSTASH_TOKEN", upstashToken.asBuildConfigString())
            buildConfigField("String", "TMDB_API_KEY", tmdbApiKey.asBuildConfigString())
            buildConfigField("String", "REAL_DEBRID_TOKEN", realDebridToken.asBuildConfigString())
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
                storePassword = keystoreProperties.getNonBlank("storePassword")!!
                keyAlias = keystoreProperties.getNonBlank("keyAlias")!!
                keyPassword = keystoreProperties.getNonBlank("keyPassword")!!
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            versionNameSuffix = "-debug"
        }

        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false

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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
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

tasks.register("qualityGateDebug") {
    group = "verification"
    description = "Runs the local quality gate (unit tests + lint) for debug."
    dependsOn("testDebugUnitTest", "lintDebug")
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

    implementation(platform("com.google.firebase:firebase-bom:33.15.0"))
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
    ksp(libs.hilt.compiler)

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
