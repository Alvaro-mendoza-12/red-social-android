package conectate.com.presentation.messages

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.StickerItem
import conectate.com.data.model.StickerPack
import conectate.com.data.repository.StickerRepository
import conectate.com.data.repository.StickerIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StickerPickerViewModel @Inject constructor(
    private val stickerRepository: StickerRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<StickerItem>>(emptyList())
    val searchResults: StateFlow<List<StickerItem>> = _searchResults.asStateFlow()

    private val _favoriteItems = MutableStateFlow<List<StickerItem>>(emptyList())
    val favoriteItems: StateFlow<List<StickerItem>> = _favoriteItems.asStateFlow()

    private val _recentItems = MutableStateFlow<List<StickerItem>>(emptyList())
    val recentItems: StateFlow<List<StickerItem>> = _recentItems.asStateFlow()

    private val _packs = MutableStateFlow<List<StickerPack>>(emptyList())
    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadedPacks = MutableStateFlow<Set<String>>(emptySet())
    val downloadedPacks: StateFlow<Set<String>> = _downloadedPacks.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        refreshAll()
        viewModelScope.launch {
            stickerRepository.favorites.collectLatest {
                _favoriteItems.value = stickerRepository.favoritesAsItems()
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isLoading.value = true
            stickerRepository.bootstrap()
            _packs.value = stickerRepository.packs.value
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
            _searchResults.value = stickerRepository.search(_query.value)
            _downloadedPacks.value = stickerRepository.downloadedPacks.value
            _isLoading.value = false
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun updateQuery(value: String) {
        _query.value = value
        viewModelScope.launch {
            _searchResults.value = stickerRepository.search(value)
        }
    }

    fun toggleFavorite(stickerId: String) {
        val item = (_searchResults.value + _favoriteItems.value + _recentItems.value)
            .firstOrNull { it.id == stickerId }
        if (item != null) {
            setFavorite(item, !isFavorite(item))
            return
        }
        viewModelScope.launch {
            stickerRepository.toggleFavorite(stickerId)
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
            _searchResults.value = stickerRepository.search(_query.value)
        }
    }

    fun isFavorite(sticker: StickerItem): Boolean =
        _favoriteItems.value.any { it.id == sticker.id } ||
            stickerRepository.isFavoriteMediaUrl(sticker.media_url)

    fun setFavorite(sticker: StickerItem, favorite: Boolean) {
        if (!favorite) {
            // Update Compose before any disk or network work starts.
            _favoriteItems.value = _favoriteItems.value.filterNot {
                it.id == sticker.id || StickerIdentity.matches(it, sticker)
            }
        }
        viewModelScope.launch {
            val change = if (favorite) {
                sticker.media_url?.let { stickerRepository.addFavoriteByMedia(it, sticker.title) }
                    ?: if (stickerRepository.setFavorite(sticker.id, true)) {
                        StickerRepository.FavoriteChange.ADDED
                    } else StickerRepository.FavoriteChange.UNAVAILABLE
            } else {
                if (stickerRepository.setFavorite(sticker.id, false)) {
                    StickerRepository.FavoriteChange.NOT_PRESENT
                } else StickerRepository.FavoriteChange.REMOVED
            }
            _actionMessage.value = when (change) {
                StickerRepository.FavoriteChange.ADDED -> "Sticker añadido a favoritos"
                StickerRepository.FavoriteChange.ALREADY_PRESENT -> "Ya tienes este sticker en favoritos"
                StickerRepository.FavoriteChange.REMOVED -> "Sticker quitado de favoritos"
                else -> "No se pudo comprobar el sticker. Inténtalo de nuevo"
            }
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
            _searchResults.value = stickerRepository.search(_query.value)
        }
    }

    fun createPack(name: String) {
        viewModelScope.launch {
            val result = stickerRepository.createPack(name)
            if (result.isFailure) {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo crear el pack"
            }
            _packs.value = stickerRepository.packs.value
        }
    }

    fun downloadPack(packId: String) {
        viewModelScope.launch {
            val result = stickerRepository.downloadPack(packId)
            if (result.isFailure) {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo descargar el pack"
            }
            _downloadedPacks.value = stickerRepository.downloadedPacks.value
            _searchResults.value = stickerRepository.search(_query.value)
            _recentItems.value = stickerRepository.recentAsItems()
        }
    }

    fun saveStickerToPack(packId: String, sticker: StickerItem) {
        viewModelScope.launch {
            val result = stickerRepository.addStickerToPack(packId, sticker)
            if (result.isFailure) {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo guardar el sticker"
            }
            _searchResults.value = stickerRepository.search(_query.value)
        }
    }

    fun copyStickerToPack(sticker: StickerItem, packId: String?) {
        viewModelScope.launch {
            val cleanPack = packId?.trim().orEmpty()
            if (cleanPack.isBlank()) {
                _actionMessage.value = "Selecciona un pack para copiar"
                return@launch
            }
            val result = stickerRepository.copyStickerToPack(sticker, cleanPack)
            if (result.isSuccess) {
                _actionMessage.value = "Sticker copiado"
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo copiar el sticker"
            }
            _searchResults.value = stickerRepository.search(_query.value)
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
        }
    }

    fun removeStickerFromPack(sticker: StickerItem, packId: String?) {
        viewModelScope.launch {
            val cleanPack = packId?.trim().orEmpty()
            if (cleanPack.isBlank()) {
                _actionMessage.value = "Selecciona un pack"
                return@launch
            }
            val result = stickerRepository.removeStickerFromPack(cleanPack, sticker.id)
            if (result.isSuccess) {
                _actionMessage.value = "Sticker quitado del pack"
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo quitar el sticker"
            }
            _searchResults.value = stickerRepository.search(_query.value)
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
        }
    }

    fun deleteSticker(stickerId: String) {
        viewModelScope.launch {
            val result = stickerRepository.deleteSticker(stickerId)
            if (result.isSuccess) {
                _actionMessage.value = "Sticker eliminado"
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo eliminar el sticker"
            }
            _searchResults.value = stickerRepository.search(_query.value)
            _favoriteItems.value = stickerRepository.favoritesAsItems()
            _recentItems.value = stickerRepository.recentAsItems()
        }
    }

    fun createStickerFromImage(uri: Uri, preferredPackId: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            val existingIds = stickerRepository.catalog.value.mapTo(mutableSetOf()) { it.id }
            val result = stickerRepository.createStickerFromImage(
                imageUri = uri,
                packId = preferredPackId
            )
            _isLoading.value = false
            if (result.isSuccess) {
                _searchResults.value = stickerRepository.search(_query.value)
                _favoriteItems.value = stickerRepository.favoritesAsItems()
                _recentItems.value = stickerRepository.recentAsItems()
                _packs.value = stickerRepository.packs.value
                _actionMessage.value = if (result.getOrNull()?.id?.let { it in existingIds } == true) {
                    "Este sticker ya está guardado"
                } else "Sticker creado"
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo crear sticker"
            }
        }
    }

    fun recordStickerSelection(sticker: StickerItem) {
        stickerRepository.registerRecentSticker(sticker.id)
        _recentItems.value = stickerRepository.recentAsItems()
    }

    fun createStickerFromEditedBytes(
        imageBytes: ByteArray,
        preferredPackId: String?,
        title: String?,
        onCreated: (StickerItem?) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            val existingIds = stickerRepository.catalog.value.mapTo(mutableSetOf()) { it.id }
            val result = stickerRepository.createStickerFromBytes(
                imageBytes = imageBytes,
                packId = preferredPackId,
                title = title?.trim()?.ifBlank { null },
                mimeType = "image/png"
            )
            _isLoading.value = false
            if (result.isSuccess) {
                val sticker = result.getOrNull()
                if (sticker != null) {
                    stickerRepository.registerRecentSticker(sticker.id)
                }
                _searchResults.value = stickerRepository.search(_query.value)
                _favoriteItems.value = stickerRepository.favoritesAsItems()
                _recentItems.value = stickerRepository.recentAsItems()
                _packs.value = stickerRepository.packs.value
                _actionMessage.value = if (sticker?.id?.let { it in existingIds } == true) {
                    "Este sticker ya está guardado"
                } else "Sticker creado"
                onCreated(sticker)
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo crear sticker"
                onCreated(null)
            }
        }
    }
}
