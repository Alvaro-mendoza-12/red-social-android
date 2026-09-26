pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val buildJava = JavaVersion.current()
require(
    buildJava.isCompatibleWith(JavaVersion.VERSION_17) &&
        buildJava <= JavaVersion.VERSION_21
) {
    "NOVVIN Android requires JDK 17-21; detected JDK ${buildJava.majorVersion}. " +
        "Set JAVA_HOME to Android Studio JBR 21 or another supported JDK before running Gradle."
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

include(":app")
