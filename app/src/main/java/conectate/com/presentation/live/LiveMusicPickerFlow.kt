package conectate.com.presentation.live

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.presentation.components.DeviceAudioPickerDialog
import conectate.com.presentation.components.PreparedAudioClip
import conectate.com.presentation.social.CommunityMusicUploadDialog
import conectate.com.presentation.social.CreateMomentViewModel
import conectate.com.presentation.social.MomentMusicPickerDialog

/**
 * Shared LIVE music flow. It intentionally reuses the exact same source
 * selector, community moderation catalog and player used by Momentos so both
 * entry points behave consistently.
 */
@Composable
internal fun LiveMusicPickerFlow(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPrepared: (uri: Uri, displayName: String, startMs: Long, endMs: Long?) -> Unit,
    catalogViewModel: CreateMomentViewModel = hiltViewModel(),
) {
    if (!visible) return

    val catalogState by catalogViewModel.musicCatalogState.collectAsStateWithLifecycle()
    val uploadState by catalogViewModel.communityMusicUploadState.collectAsStateWithLifecycle()
    var showDeviceLibrary by remember { mutableStateOf(false) }
    var showCommunityUpload by remember { mutableStateOf(false) }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCommunityAudio by remember { mutableStateOf<PreparedAudioClip?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingFileUri = uri
        if (uri != null) showDeviceLibrary = true
    }

    when {
        showCommunityUpload -> CommunityMusicUploadDialog(
            state = uploadState,
            initialAudio = pendingCommunityAudio,
            onSubmit = catalogViewModel::uploadCommunityMusic,
            onDismiss = {
                if (!uploadState.isUploading) {
                    showCommunityUpload = false
                    pendingCommunityAudio = null
                    catalogViewModel.clearCommunityMusicUploadState()
                }
            },
        )

        showDeviceLibrary -> DeviceAudioPickerDialog(
            initialUri = pendingFileUri,
            title = "Mi música",
            confirmLabel = "Usar en el LIVE",
            maxClipDurationMs = 60_000L,
            defaultClipDurationMs = 30_000L,
            allowFullTrack = false,
            catalogTracks = emptyList(),
            enableOpenCatalog = false,
            onBrowseFiles = { filePicker.launch(arrayOf("audio/*")) },
            onDismiss = {
                showDeviceLibrary = false
                pendingFileUri = null
            },
            onClipReady = { clip ->
                onPrepared(clip.uri, clip.displayName, 0L, null)
                onDismiss()
            },
        )

        else -> MomentMusicPickerDialog(
            state = catalogState,
            contentDurationMs = 60_000L,
            hasOriginalAudio = true,
            initialSelection = null,
            onSearch = catalogViewModel::searchApprovedMusic,
            onLoadMore = catalogViewModel::loadMoreApprovedMusic,
            onToggleFavorite = catalogViewModel::toggleMusicFavorite,
            onReport = catalogViewModel::reportMusic,
            onNotInterested = catalogViewModel::hideMusicRecommendation,
            onPreviewStarted = catalogViewModel::recordMusicPreviewStarted,
            onOpenCommunityUpload = { showCommunityUpload = true },
            onOpenDeviceMusic = { showDeviceLibrary = true },
            onDismiss = onDismiss,
            onRemove = onDismiss,
            onSelect = { selection ->
                val audioUrl = selection.track.audioUrl.trim()
                if (audioUrl.isNotBlank()) {
                    onPrepared(
                        Uri.parse(audioUrl),
                        "${selection.track.title} — ${selection.track.artist}",
                        selection.startMs,
                        selection.endMs,
                    )
                    onDismiss()
                }
            },
        )
    }
}
