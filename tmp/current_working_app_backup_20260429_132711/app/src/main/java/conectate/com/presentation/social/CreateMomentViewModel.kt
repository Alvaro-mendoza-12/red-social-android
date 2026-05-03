package conectate.com.presentation.social

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.MomentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CreateMomentViewModel @Inject constructor(
    private val repository: MomentRepository
) : ViewModel() {

    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    private val _galleryMedia = MutableStateFlow<List<GalleryMedia>>(emptyList())
    val galleryMedia = _galleryMedia.asStateFlow()
    
    // State for trimming
    private val _trimmingState = MutableStateFlow<TrimmingState>(TrimmingState.Idle)
    val trimmingState = _trimmingState.asStateFlow()

    fun loadGalleryMedia(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val mediaList = mutableListOf<GalleryMedia>()
            
            // 1. Fetch Images
            val imageProjection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DATE_ADDED
            )
            val imageUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            
            try {
                context.contentResolver.query(imageUri, imageProjection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val contentUri = android.content.ContentUris.withAppendedId(imageUri, id)
                        
                        mediaList.add(GalleryMedia(id, contentUri, MediaType.IMAGE, 0L, dateAdded))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CreateMomentViewModel", "Error fetching images", e)
            }

            // 2. Fetch Videos
            val videoProjection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DURATION,
                android.provider.MediaStore.Video.Media.DATE_ADDED
            )
            val videoUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            
            try {
                context.contentResolver.query(videoUri, videoProjection, null, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)
                    val dateColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_ADDED)
                    
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val duration = cursor.getLong(durationColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val contentUri = android.content.ContentUris.withAppendedId(videoUri, id)
                        
                        mediaList.add(GalleryMedia(id, contentUri, MediaType.VIDEO, duration, dateAdded))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CreateMomentViewModel", "Error fetching videos", e)
            }

            // 3. Sort and Limit
            val sortedList = mediaList.sortedByDescending { it.dateAdded }.take(500)
            
            _galleryMedia.value = sortedList
            android.util.Log.d("CreateMomentViewModel", "Total media loaded: ${sortedList.size}")
        }
    }

    fun uploadMoment(
        uri: Uri,
        mediaType: String,
        durationHours: Int,
        durationType: String,
        visibility: String,
        visibilityType: String,
        excludedIds: List<String>,
        caption: String? = null,
        allowReplies: Boolean = true,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isUploading.value = true
            val result = repository.createMoment(
                mediaUri = uri, 
                mediaType = mediaType, 
                durationHours = durationHours, 
                durationType = durationType,
                visibility = visibility, 
                visibilityType = visibilityType,
                excludedIds = excludedIds,
                caption = caption,
                allowReplies = allowReplies
            )
            _isUploading.value = false
            if (result.isSuccess) {
                onSuccess()
                // CRITICAL: Refresh moments list immediately
                try {
                    repository.refreshMoments()
                    android.util.Log.d("CreateMomentViewModel", "Moments list refreshed after creation")
                } catch (e: Exception) {
                    android.util.Log.e("CreateMomentViewModel", "Error refreshing moments after creation", e)
                }
            } else {
                val errorMessage = result.exceptionOrNull()?.message ?: "No se pudo publicar el momento"
                onError(errorMessage)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun trimVideo(
        context: android.content.Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        // Fix: Transformer requires a Looper (Main Thread) to initialize and start
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            _trimmingState.value = TrimmingState.Trimming(0f)
            
            try {
                // File operations on IO
                val (outputFile) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val outputDir = context.cacheDir
                    val f = java.io.File(outputDir, "trimmed_${System.currentTimeMillis()}.mp4")
                    // Return as list/pair or just file
                    listOf(f)
                }
                
                val outputUri = Uri.fromFile(outputFile)

                val mediaItem = androidx.media3.common.MediaItem.fromUri(inputUri)
                    .buildUpon()
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()

                val transformer = androidx.media3.transformer.Transformer.Builder(context)
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult) {
                            _trimmingState.value = TrimmingState.Idle
                             // Already on Main
                             onSuccess(Uri.fromFile(outputFile))
                        }

                        override fun onError(composition: androidx.media3.transformer.Composition, result: androidx.media3.transformer.ExportResult, exception: androidx.media3.transformer.ExportException) {
                            _trimmingState.value = TrimmingState.Error(exception.message ?: "Error trimming")
                            onError(exception.message ?: "Error trimming")
                        }
                    })
                    .build()

                transformer.start(mediaItem, outputFile.absolutePath)
                
            } catch (e: Exception) {
                _trimmingState.value = TrimmingState.Error(e.message ?: "Unknown error")
                onError(e.message ?: "Unknown error")
            }
        }
    }
}

data class GalleryMedia(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val duration: Long,
    val dateAdded: Long
)

enum class MediaType {
    IMAGE, VIDEO
}

sealed class TrimmingState {
    object Idle : TrimmingState()
    data class Trimming(val progress: Float) : TrimmingState()
    data class Error(val message: String) : TrimmingState()
}
