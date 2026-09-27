import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Android Studio and the wrapper can use different Gradle daemons for this
// checkout. Gradle does not serialize their writes to app/build, so one build
// can remove generated Hilt classes or R.jar while the other reads them.
// Hold a cross-process lock for the entire build, including IDE builds.
val novvinBuildLockPath = settingsDir.toPath().resolve(".gradle/novvin-build.lock")
java.nio.file.Files.createDirectories(novvinBuildLockPath.parent)
val novvinBuildLockChannel = FileChannel.open(
    novvinBuildLockPath,
    StandardOpenOption.CREATE,
    StandardOpenOption.WRITE,
)
val novvinBuildLock = try {
    var acquired: java.nio.channels.FileLock? = null
    var announced = false
    while (acquired == null) {
        acquired = try {
            novvinBuildLockChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (acquired == null) {
            if (!announced) {
                logger.lifecycle("Novvin: waiting for another build to finish in this checkout...")
                announced = true
            }
            Thread.sleep(1_000)
        }
    }
    acquired
} catch (error: Throwable) {
    novvinBuildLockChannel.close()
    throw error
}
gradle.buildFinished {
    novvinBuildLock.release()
    novvinBuildLockChannel.close()
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
