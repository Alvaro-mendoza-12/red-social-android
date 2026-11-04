package conectate.com.presentation.messages

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
internal fun CameraGalleryShortcut(allowedTypes: Set<String>, enabled: Boolean, active: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val videoOnly = "image" !in allowedTypes && "video" in allowedTypes
    val collection = when {
        "image" in allowedTypes -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        videoOnly -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> null
    }
    val (uri, revision) = rememberLatestCameraGalleryItem(collection, active)
    var failed by remember(uri, revision) { mutableStateOf(false) }
    val request = remember(context, uri, revision, videoOnly) {
        ImageRequest.Builder(context).data(uri).size(144).crossfade(false).apply {
            if (videoOnly) decoderFactory(VideoFrameDecoder.Factory())
        }.build()
    }
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(56.dp).semantics { contentDescription = "Abrir galería" }) {
        Box(Modifier.size(48.dp).alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = .35f))
            .border(1.dp, Color.White.copy(alpha = .7f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            if (uri != null && !failed) AsyncImage(request, null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, onError = { failed = true })
            else Icon(Icons.Default.Image, null, tint = Color.White)
        }
    }
}

@Composable
private fun rememberLatestCameraGalleryItem(collection: Uri?, active: Boolean): Pair<Uri?, Int> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // New files can be notified under external_primary or another mounted
    // volume rather than the aggregate external collection used by the query.
    val notificationRoot = remember { Uri.parse("content://${MediaStore.AUTHORITY}") }
    var revision by remember(context, collection) { mutableIntStateOf(0) }
    var uri by remember(context, collection) { mutableStateOf<Uri?>(null) }
    DisposableEffect(context, owner, collection, active) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        val lifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        val registered = if (collection != null && active) runCatching {
            context.contentResolver.registerContentObserver(notificationRoot, true, observer)
            true
        }.getOrDefault(false) else false
        owner.lifecycle.addObserver(lifecycle)
        onDispose {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
            owner.lifecycle.removeObserver(lifecycle)
        }
    }
    LaunchedEffect(context, collection, revision, active) {
        if (active && collection != null) uri = loadLatestCameraGalleryItem(context, collection)
    }
    return uri to revision
}

/** One accessible item only. Android enforces full, selected-photo or own-media access. */
internal suspend fun loadLatestCameraGalleryItem(context: Context, collection: Uri): Uri? = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        val result = try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val conditions = mutableListOf("${MediaStore.MediaColumns.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= 29) conditions += "${MediaStore.MediaColumns.IS_PENDING} = 0"
            if (Build.VERSION.SDK_INT >= 30) conditions += "${MediaStore.MediaColumns.IS_TRASHED} = 0"
            val selection = conditions.joinToString(" AND ")
            val cursor = if (Build.VERSION.SDK_INT >= 26) {
                val args = Bundle().apply {
                    // Framework synthesis can append DESC only to the last column.
                    // Specify both directions so DATE_ADDED never defaults to ASC.
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                }
                context.contentResolver.query(collection, projection, args, cancellation)
            } else context.contentResolver.query(collection, projection, selection, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC LIMIT 1", cancellation)
            cursor?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }
        } catch (_: SecurityException) { null }
        catch (_: android.os.OperationCanceledException) { null }
        catch (failure: Exception) {
            android.util.Log.w("CameraGallery", "Gallery thumbnail unavailable: ${failure.javaClass.simpleName}")
            null
        }
        if (continuation.isActive) continuation.resume(result)
    }
}
