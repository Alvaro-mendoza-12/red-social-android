plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.io.FileInputStream
import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
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
        minSdk = 26
        targetSdk = 35
        versionCode = 27  
        versionName = "2.1.7"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            
            buildConfigField("String", "SUPABASE_URL", "\"https://your-supabase-url.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"your-anon-key\"")
            buildConfigField("String", "FCM_SERVER_KEY", "\"your-fcm-server-key\"")
        }
        
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            
            buildConfigField("String", "SUPABASE_URL", "\"https://your-supabase-url.supabase.co\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"your-anon-key\"")
            buildConfigField("String", "FCM_SERVER_KEY", "\"your-fcm-server-key\"")
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/stable/AndroidManifest.xml")
            java.setSrcDirs(listOf("src/stable/java"))
            res.setSrcDirs(listOf("src/main/res"))
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
