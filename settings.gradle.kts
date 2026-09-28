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
abstract class NovvinBuildLockService : org.gradle.api.services.BuildService<NovvinBuildLockService.Parameters>, AutoCloseable {
    interface Parameters : org.gradle.api.services.BuildServiceParameters {
        val lockPath: org.gradle.api.provider.Property<String>
    }

    private var channel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    @Synchronized
    fun acquire() {
        if (lock?.isValid == true) return
        val path = java.nio.file.Path.of(parameters.lockPath.get())
        java.nio.file.Files.createDirectories(path.parent)
        val openedChannel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel = openedChannel
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
        var announced = false
        try {
            while (lock == null) {
                lock = try { openedChannel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) {
                    if (!announced) {
                        org.gradle.api.logging.Logging.getLogger(NovvinBuildLockService::class.java)
                            .lifecycle("Novvin: waiting for another build to finish in this checkout...")
                        announced = true
                    }
                    check(System.nanoTime() < deadline) {
                        "Otra compilación de Novvin sigue ocupando este proyecto después de 10 minutos. " +
                            "Cancela esa compilación en Android Studio o ejecuta gradlew --stop y vuelve a intentar."
                    }
                    Thread.sleep(1_000)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        try { lock?.takeIf { it.isValid }?.release() } finally {
            lock = null
            channel?.close()
            channel = null
        }
    }
}

// Gradle owns cleanup, including model requests and failed builds. Capturing a
// settings-script channel in buildFinished could retain a lock in a daemon.
val novvinBuildLock = gradle.sharedServices.registerIfAbsent("novvinBuildLock", NovvinBuildLockService::class.java) {
    parameters.lockPath.set(settingsDir.toPath().resolve(".gradle/novvin-build.lock").toString())
}
novvinBuildLock.get().acquire()
gradle.beforeProject {
    tasks.configureEach { usesService(novvinBuildLock) }
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
