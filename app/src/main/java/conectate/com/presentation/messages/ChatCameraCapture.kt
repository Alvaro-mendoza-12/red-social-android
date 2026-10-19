package conectate.com.presentation.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.TorchState
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val CHAT_CAMERA_MAX_DURATION_MS = 60_000L
internal const val CHAT_CAMERA_MAX_BYTES = 100L * 1024 * 1024

internal fun createChatCaptureFile(context: Context, video: Boolean): File {
    val directory = File(context.cacheDir, "file-provider/chat-captures")
    check(directory.isDirectory || directory.mkdirs()) { "No se pudo preparar el almacenamiento de la cámara" }
    return File(directory, "capture-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}")
}

/** Owns only this screen's camera. Results go to the existing editor, never directly to send. */
@Composable
internal fun ChatCameraCapture(
    allowedTypes: Set<String> = setOf("image", "video"),
    onClose: () -> Unit,
    onGallery: () -> Unit,
    onCaptured: (OutgoingMessageMedia, Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val capturedCallback by rememberUpdatedState(onCaptured)
    val session = remember(lifecycleOwner) {
        ChatCameraCaptureSession(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var cameraAllowed by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf("image" !in allowedTypes && "video" in allowedTypes) }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var streaming by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var flashPending by remember { mutableStateOf(false) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var retryCamera by remember { mutableIntStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var audioPending by remember { mutableStateOf(false) }
    var audioRequestToken by remember { mutableStateOf<Long?>(null) }
    var finalizing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordingDuration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val controller = remember(context) {
        LifecycleCameraController(context.applicationContext).apply {
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            videoCaptureQualitySelector = QualitySelector.from(
                Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
            )
        }
    }
    val preview = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            this.controller = controller
        }
    }
    fun deliver(file: File, type: String, duration: Long, token: Long) {
        if (!session.accepts(token)) {
            file.delete()
            return
        }
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            error = "La captura quedó vacía. Comprueba el espacio disponible y vuelve a intentar."
            return
        }
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", file) }.getOrElse {
            file.delete()
            if (session.accepts(token)) error = "No se pudo abrir la captura. Inténtalo de nuevo."
            return
        }
        capturedCallback(OutgoingMessageMedia(uri, type), duration)
    }
    fun finishRecording() {
        val current = recording ?: return
        if (finalizing) return
        finalizing = true
        runCatching { current.stop() }.onFailure {
            finalizing = false; recording = null
            if (session.isOpen) error = "No se pudo finalizar el video. Inténtalo de nuevo."
        }
    }
    fun startRecording() {
        if (!streaming || capturing || recording != null || finalizing || flashPending || !foreground) return
        val token = session.begin() ?: return
        error = null
        recordingDuration = 0L
        val file = runCatching { createChatCaptureFile(context, true) }.getOrElse {
            error = "No se pudo preparar el video. Libera espacio y vuelve a intentar."
            return
        }
        val audioAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!audioAllowed) error = "El video se grabará sin sonido. Puedes habilitar el micrófono en los permisos de la app."
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(CHAT_CAMERA_MAX_DURATION_MS)
            .setFileSizeLimit(CHAT_CAMERA_MAX_BYTES)
            .build()
        runCatching {
            controller.startRecording(options, androidx.camera.view.video.AudioConfig.create(audioAllowed), executor) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> recordingDuration = TimeUnit.NANOSECONDS.toMillis(
                        event.recordingStats.recordedDurationNanos,
                    )
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        finalizing = false
                        if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("ChatCameraCapture",
                            "video_finalize error=${event.error} duration_ms=${TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)} bytes=${event.recordingStats.numBytesRecorded} active=${session.accepts(token)}")
                        val validLimit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                        if (!event.hasError() || validLimit) {
                            deliver(file, "video", TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos), token)
                        } else {
                            file.delete()
                            if (session.accepts(token)) error = "No se pudo grabar el video. Comprueba espacio y permisos e inténtalo otra vez."
                        }
                    }
                }
            }
        }.onSuccess { recording = it }.onFailure {
            file.delete()
            error = "No se pudo iniciar la grabación. Comprueba que la cámara esté disponible."
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (!granted) error = "Permite el acceso a la cámara para tomar fotos y videos. También puedes abrir la galería."
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val token = audioRequestToken
        audioPending = false
        audioRequestToken = null
        if (token != null && session.accepts(token) && video) startRecording()
    }
    LaunchedEffect(Unit) {
        if (!cameraAllowed && !permissionRequested) {
            permissionRequested = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(controller, lifecycleOwner, cameraAllowed, video, front, retryCamera) {
        streaming = false
        hasFlash = false
        canSwitchCamera = false
        if (cameraAllowed) {
            runCatching {
                controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                controller.setEnabledUseCases(if (video) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
                controller.bindToLifecycle(lifecycleOwner)
            }.onFailure { error = "La cámara no está disponible. Cierra otras apps que la estén usando e inténtalo otra vez." }
        }
        val observer = Observer<PreviewView.StreamState> {
            streaming = it == PreviewView.StreamState.STREAMING
            if (streaming) {
                hasFlash = controller.cameraInfo?.hasFlashUnit() == true
                canSwitchCamera = runCatching {
                    controller.hasCamera(if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false)
            }
        }
        val torchObserver = Observer<Int> { torchOn = it == TorchState.ON }
        preview.previewStreamState.observe(lifecycleOwner, observer)
        controller.torchState.observe(lifecycleOwner, torchObserver)
        onDispose {
            preview.previewStreamState.removeObserver(observer)
            controller.torchState.removeObserver(torchObserver)
            runCatching { controller.unbind() }
        }
    }
    LaunchedEffect(flash, video, streaming, front, hasFlash, foreground) {
        if (!streaming || !session.isOpen || !foreground) return@LaunchedEffect
        flashPending = true
        try {
            controller.imageCaptureFlashMode = if (hasFlash && !video && flash == ImageCapture.FLASH_MODE_AUTO) {
                ImageCapture.FLASH_MODE_AUTO
            } else ImageCapture.FLASH_MODE_OFF
            if (hasFlash) {
                // ON provides visible illumination for photos as well as video. AUTO flashes only at capture.
                val change = controller.enableTorch(chatCameraUsesTorch(flash, hasFlash))
                suspendCancellableCoroutine<Unit> { continuation ->
                    change.addListener({
                        try {
                            change.get()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }, executor)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (session.isOpen) {
                flash = ImageCapture.FLASH_MODE_OFF
                error = "No se pudo activar el flash. Puedes tomar la foto sin flash o volver a intentarlo."
            }
        } finally {
            flashPending = false
        }
    }
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { session.start(); foreground = true }
                Lifecycle.Event.ON_RESUME -> { cameraAllowed = hasCameraPermission(context) }
                Lifecycle.Event.ON_STOP -> {
                    session.stop(); foreground = false
                    flash = ImageCapture.FLASH_MODE_OFF
                    if (recording != null || capturing) error = "La captura se canceló al salir de la cámara. Puedes intentarlo de nuevo."
                    finishRecording()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            session.close()
            runCatching { recording?.stop() }
            recording = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    fun close() {
        session.close()
        runCatching { recording?.stop() }
        onClose()
    }
    BackHandler { close() }
    val busy = capturing || recording != null || finalizing || audioPending
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraAllowed) AndroidView(
            factory = { preview },
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Vista previa de cámara"
                stateDescription = if (streaming) "Cámara lista" else "Iniciando cámara"
            },
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { close() }, enabled = !capturing) {
                    Icon(Icons.Default.Close, "Cerrar cámara", tint = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val flashLabel = chatCameraFlashLabel(flash, hasFlash, torchOn, flashPending)
                    IconButton(onClick = {
                        error = null
                        flash = nextChatCameraFlash(flash, video)
                    }, enabled = foreground && streaming && hasFlash && !capturing && !finalizing && !audioPending && !flashPending,
                        modifier = Modifier.semantics { stateDescription = flashLabel }) {
                        Icon(when (flash) {
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            else -> Icons.Default.FlashOff
                        }, "Flash", tint = if (hasFlash) Color.White else Color.Gray)
                    }
                    Text(flashLabel, color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.background(Color.Black.copy(alpha = .65f)).padding(4.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (!cameraAllowed) Button(
                onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Permitir cámara") }
            if (cameraAllowed && !streaming && error == null) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally), color = Color.White,
            )
            error?.let { Text(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .8f)).padding(12.dp)) }
            if (cameraAllowed && !streaming && error != null) TextButton(
                onClick = { error = null; retryCamera++ }, enabled = !busy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Reintentar cámara", color = Color.White) }
            if (recording != null) Text(
                if (recordingDuration == 0L) "Preparando video…" else "%02d:%02d".format(recordingDuration / 60_000, recordingDuration / 1_000 % 60),
                color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).background(Color.Red).padding(8.dp).semantics {
                    contentDescription = "Duración de grabación"
                    stateDescription = "${recordingDuration / 1_000} segundos grabados"
                },
            )
            if (audioPending || finalizing) Text(
                if (audioPending) "Esperando permiso del micrófono…" else "Preparando video…",
                color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally)
                    .background(Color.Black.copy(alpha = .65f)).padding(8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { session.close(); onGallery() }, enabled = !busy) { Icon(Icons.Default.Image, "Abrir galería", tint = Color.White) }
                FilledIconButton(
                    onClick = {
                        if (video) {
                            if (recording != null) finishRecording()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                            else {
                                audioRequestToken = session.begin()
                                audioPending = audioRequestToken != null
                                if (audioPending) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else if (!capturing) {
                            error = null
                            val file = runCatching { createChatCaptureFile(context, false) }.getOrElse {
                                error = "No se pudo preparar la foto. Libera espacio y vuelve a intentar."
                                return@FilledIconButton
                            }
                            val token = session.begin() ?: return@FilledIconButton
                            capturing = true
                            runCatching {
                                controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            capturing = false
                                            deliver(file, "image", 0L, token)
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            capturing = false
                                            file.delete()
                                            if (session.accepts(token)) error = "No se pudo tomar la foto. Inténtalo otra vez."
                                        }
                                    })
                            }.onFailure {
                                capturing = false
                                file.delete()
                                error = "No se pudo tomar la foto. Inténtalo otra vez."
                            }
                        }
                    }, enabled = foreground && streaming && !capturing && !flashPending && !finalizing && !audioPending,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording != null) Color.Red else Color.White, contentColor = Color.Black),
                    modifier = Modifier.size(80.dp).border(3.dp, Color.White, CircleShape).padding(6.dp),
                ) { Icon(if (recording != null) Icons.Default.Stop else if (video) Icons.Default.Videocam else Icons.Default.CameraAlt,
                    if (recording != null) "Detener grabación" else if (video) "Grabar video" else "Tomar foto", Modifier.size(32.dp)) }
                IconButton(onClick = { error = null; flash = ImageCapture.FLASH_MODE_OFF; front = !front }, enabled = streaming && canSwitchCamera && !busy && !flashPending) {
                    Icon(Icons.Default.Cameraswitch, "Cambiar cámara", tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center) {
                if ("video" in allowedTypes) TextButton(onClick = { error = null; video = true; flash = ImageCapture.FLASH_MODE_OFF }, enabled = !busy) {
                    Text("Video", color = if (video) Color.Yellow else Color.White)
                }
                if ("image" in allowedTypes) TextButton(onClick = { error = null; video = false; flash = ImageCapture.FLASH_MODE_OFF }, enabled = !busy) {
                    Text("Foto", color = if (!video) Color.Yellow else Color.White)
                }
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
