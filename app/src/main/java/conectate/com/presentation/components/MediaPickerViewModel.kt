package conectate.com.presentation.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.presentation.social.GalleryMedia
import conectate.com.presentation.social.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaPickerViewModel : ViewModel() {
    private val _galleryMedia = MutableStateFlow<List<GalleryMedia>>(emptyList())
    val galleryMedia = _galleryMedia.asStateFlow()

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
                android.util.Log.e("MediaPickerViewModel", "Error fetching images", e)
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
                android.util.Log.e("MediaPickerViewModel", "Error fetching videos", e)
            }

            // 3. Sort and Limit
            val sortedList = mediaList.sortedByDescending { it.dateAdded }.take(500)
            
            _galleryMedia.value = sortedList
        }
    }
}
