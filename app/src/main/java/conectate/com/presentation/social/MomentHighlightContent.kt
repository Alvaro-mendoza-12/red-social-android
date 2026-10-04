package conectate.com.presentation.social

import conectate.com.data.model.Moment
import conectate.com.data.model.MomentHighlightItemPreview

internal fun MomentHighlightItemPreview.asMoment(): Moment = Moment(
    id = momentId.ifBlank { id }, userId = userId, mediaUrl = mediaUrl, mediaType = mediaType,
    thumbnailUrl = thumbnailUrl, caption = caption, createdAt = createdAt, expiresAt = expiresAt,
    username = username, userDisplayName = userDisplayName, userAvatarUrl = userAvatarUrl,
    overlayText = overlayText, overlayColor = overlayColor, overlayFont = overlayFont,
    overlayScale = overlayScale, overlayX = overlayX, overlayY = overlayY,
    musicUrl = musicUrl, musicAudioUrl = musicAudioUrl, musicId = musicId,
    musicTitle = musicTitle, musicArtist = musicArtist, musicSource = musicSource,
    musicPresentation = musicPresentation, musicArtworkUrl = musicArtworkUrl,
    musicSourceUrl = musicSourceUrl, musicLicense = musicLicense, musicLicenseUrl = musicLicenseUrl,
    musicStartMs = musicStartMs, musicDurationMs = musicDurationMs, musicEndMs = musicEndMs,
    musicVolume = musicVolume, originalAudioVolume = originalAudioVolume,
    musicFadeInMs = musicFadeInMs, musicFadeOutMs = musicFadeOutMs,
    duckOriginalAudio = duckOriginalAudio, musicEmbedded = musicEmbedded, displayDurationMs = displayDurationMs,
)
