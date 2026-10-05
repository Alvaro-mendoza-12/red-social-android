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
import java.util.concurrent.atomic.AtomicBoolean

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
    val active = remember { AtomicBoolean(true) }
    var cameraAllowed by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf("image" !in allowedTypes && "video" in allowedTypes) }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var streaming by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
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
    fun deliver(file: File, type: String, duration: Long) {
        if (!active.get() || !file.isFile || file.length() == 0L) {
            file.delete()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        capturedCallback(OutgoingMessageMedia(uri, type), duration)
    }
    fun startRecording() {
        if (!streaming || capturing || recording != null || !active.get()) return
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
                        if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("ChatCameraCapture",
                            "video_finalize error=${event.error} duration_ms=${TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)} bytes=${event.recordingStats.numBytesRecorded} active=${active.get()}")
                        val validLimit = event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                        if (!event.hasError() || validLimit) {
                            deliver(file, "video", TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos))
                        } else {
                            file.delete()
                            if (active.get()) error = "No se pudo grabar el video. Comprueba espacio y permisos e inténtalo otra vez."
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
        if (active.get() && video) startRecording()
    }
    LaunchedEffect(Unit) {
        if (!cameraAllowed && !permissionRequested) {
            permissionRequested = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(controller, lifecycleOwner, cameraAllowed, video, front) {
        streaming = false
        if (cameraAllowed) {
            runCatching {
                controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                controller.setEnabledUseCases(if (video) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
                controller.bindToLifecycle(lifecycleOwner)
            }.onFailure { error = "La cámara no está disponible. Cierra otras apps que la estén usando e inténtalo otra vez." }
        }
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        preview.previewStreamState.observe(lifecycleOwner, observer)
        onDispose {
            preview.previewStreamState.removeObserver(observer)
            controller.unbind()
        }
    }
    LaunchedEffect(flash, video, streaming, front) {
        if (streaming) {
            runCatching {
                controller.imageCaptureFlashMode = flash
                controller.enableTorch(video && !front && flash == ImageCapture.FLASH_MODE_ON)
            }.onFailure { error = "Esta cámara no permite activar el flash." }
        }
    }
    DisposableEffect(lifecycleOwner) {
        active.set(true)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) recording?.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            active.set(false)
            recording?.stop()
            recording = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    fun close() {
        active.set(false)
        recording?.stop()
        onClose()
    }
    BackHandler { close() }
    val busy = capturing || recording != null
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
                IconButton(onClick = {
                    flash = if (video) {
                        if (flash == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                    } else when (flash) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }, enabled = streaming && !front && !capturing) {
                    Icon(when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        else -> Icons.Default.FlashOff
                    }, "Flash", tint = Color.White)
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
            if (recording != null) Text(
                if (recordingDuration == 0L) "Preparando video…" else "%02d:%02d".format(recordingDuration / 60_000, recordingDuration / 1_000 % 60),
                color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).background(Color.Red).padding(8.dp).semantics {
                    contentDescription = "Duración de grabación"
                    stateDescription = "${recordingDuration / 1_000} segundos grabados"
                },
            )
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onGallery, enabled = !busy) { Icon(Icons.Default.Image, "Abrir galería", tint = Color.White) }
                FilledIconButton(
                    onClick = {
                        if (video) {
                            if (recording != null) recording?.stop()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                            else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else if (!capturing) {
                            error = null
                            val file = runCatching { createChatCaptureFile(context, false) }.getOrElse {
                                error = "No se pudo preparar la foto. Libera espacio y vuelve a intentar."
                                return@FilledIconButton
                            }
                            capturing = true
                            runCatching {
                                controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            capturing = false
                                            deliver(file, "image", 0L)
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            capturing = false
                                            file.delete()
                                            if (active.get()) error = "No se pudo tomar la foto. Inténtalo otra vez."
                                        }
                                    })
                            }.onFailure {
                                capturing = false
                                file.delete()
                                error = "No se pudo tomar la foto. Inténtalo otra vez."
                            }
                        }
                    }, enabled = streaming && !capturing,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (recording != null) Color.Red else Color.White, contentColor = Color.Black),
                    modifier = Modifier.size(80.dp).border(3.dp, Color.White, CircleShape).padding(6.dp),
                ) { Icon(if (recording != null) Icons.Default.Stop else if (video) Icons.Default.Videocam else Icons.Default.CameraAlt,
                    if (recording != null) "Detener grabación" else if (video) "Grabar video" else "Tomar foto", Modifier.size(32.dp)) }
                IconButton(onClick = { error = null; front = !front }, enabled = streaming && !busy) {
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
