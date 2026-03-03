plugins {
    id("com.android.application") version "8.5.1"
    id("org.jetbrains.kotlin.android") version "1.9.20"
    id("com.google.dagger.hilt.android") version "2.48"
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
    id("com.google.gms.google-services") version "4.4.0"
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "conectate.com"
    compileSdk = 35

    defaultConfig {
        applicationId = "conectate.com"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.1.9"

        // Expose Supabase secrets via BuildConfig (loaded from secrets.properties)
        val props = Properties()
        val secretsFile = rootProject.file("secrets.properties")
        if (secretsFile.exists()) {
            props.load(FileInputStream(secretsFile))
            val supabaseUrl = props.getProperty("SUPABASE_URL") ?: ""
            val supabaseAnon = props.getProperty("SUPABASE_ANON_KEY") ?: ""
            buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnon\"")
        } else {
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
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
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // Supabase - HTTP client with Ktor
    // implementation("io.github.supabase:supabase-kt:2.1.0") // Removed - using custom Ktor implementation
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.0.4")
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.0.4") // Required for session/auth integration with Realtime
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.4") // Helpful for typed queries if needed, but mainly for Realtime config
    implementation("io.github.jan-tennert.supabase:storage-kt:2.0.4")
    
    // Ktor for Supabase HTTP client
    // NOTE: CIO engine is REQUIRED for WebSocket (Realtime) support
    implementation("io.ktor:ktor-client-cio:2.3.5")
    implementation("io.ktor:ktor-client-okhttp:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")
    implementation("io.ktor:ktor-client-logging:2.3.5")

    // Remove any Firebase Play Services coroutine dependency remnants
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Firebase Messaging
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")

    // Hilt for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Coil for Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")

    // ExoPlayer for Video Playback
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")

    // Accompanist for Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // ML Kit - Sticker cutout (automatic foreground segmentation)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // Pure WebRTC for Voice Rooms
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    
    // DataStore & Settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.russhwolf:multiplatform-settings:1.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager for Offline Sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
