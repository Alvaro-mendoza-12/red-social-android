package conectate.com.presentation.components

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class FullScreenMediaViewerTest {

    @Test
    fun zoomWorksWhenOriginalLoadsWithoutPreviewAndWhenOnlyPreviewLoads() {
        assertTrue(canZoomLoadedImage(true, true, false, false))
        assertTrue(canZoomLoadedImage(true, false, true, false))
        assertTrue(canZoomLoadedImage(true, false, false, true))
        assertFalse(canZoomLoadedImage(true, false, false, false))
        assertFalse(canZoomLoadedImage(false, true, true, true))
    }

    @Test
    fun socialOverlay_usesMeasuredInsetPlusClearanceWithoutScreenSpecificOffset() {
        assertEquals(40.dp, reliableBottomSafePadding(24.dp, 28.dp, 12.dp))
        assertEquals(60.dp, reliableBottomSafePadding(48.dp, 28.dp, 12.dp))
        assertEquals(28.dp, reliableBottomSafePadding(0.dp, 28.dp, 0.dp))
    }

    @Test
    fun socialOverlay_addsOnlyViewportBasedClearanceOnTallWindows() {
        assertEquals(0.dp, tallPostOverlayClearance(640))
        assertEquals(0.dp, tallPostOverlayClearance(800))
        assertEquals(12.dp, tallPostOverlayClearance(820))
        assertEquals(24.dp, tallPostOverlayClearance(873))
        assertEquals(24.dp, tallPostOverlayClearance(915))
    }

    @Test
    fun fitMediaInViewport_preservesLandscapeAndPortraitAspectRatios() {
        val viewport = MediaDimensions(width = 1080f, height = 1800f)

        val landscape = fitMediaInViewport(viewport, MediaDimensions(1920f, 1080f))
        val portrait = fitMediaInViewport(viewport, MediaDimensions(1080f, 2400f))

        assertEquals(1080f, landscape.width, 0.01f)
        assertEquals(607.5f, landscape.height, 0.01f)
        assertEquals(810f, portrait.width, 0.01f)
        assertEquals(1800f, portrait.height, 0.01f)
    }

    @Test
    fun panBounds_followRenderedMediaInsteadOfTheFullScreenCanvas() {
        val viewport = MediaDimensions(width = 1080f, height = 1800f)
        val fittedMedia = MediaDimensions(width = 1080f, height = 607.5f)

        assertEquals(
            MediaPanBounds(horizontal = 0f, vertical = 0f),
            calculateMediaPanBounds(viewport, fittedMedia, MIN_MEDIA_ZOOM)
        )

        val zoomedBounds = calculateMediaPanBounds(viewport, fittedMedia, MAX_MEDIA_ZOOM)
        assertEquals(1080f, zoomedBounds.horizontal, 0.01f)
        assertEquals(11.25f, zoomedBounds.vertical, 0.01f)
        assertEquals(
            MediaTranslation(1080f, -11.25f),
            clampMediaTranslation(MediaTranslation(50_000f, -50_000f), zoomedBounds)
        )
    }

    @Test
    fun transformGesture_clampsExtremeZoomAndPan() {
        val transformed = applyMediaTransformGesture(
            current = MediaTransform(),
            centroid = MediaTranslation(500f, 500f),
            pan = MediaTranslation(100_000f, -100_000f),
            zoomChange = 100f,
            viewport = MediaDimensions(1000f, 1000f),
            fittedMedia = MediaDimensions(1000f, 500f)
        )

        assertEquals(MAX_MEDIA_ZOOM, transformed.scale, 0f)
        assertEquals(MediaTranslation(1000f, -250f), transformed.translation)
    }

    @Test
    fun doubleTap_zoomsAroundTapThenResets() {
        val viewport = MediaDimensions(1000f, 1000f)
        val fittedMedia = MediaDimensions(1000f, 500f)

        val zoomed = doubleTapMediaTransform(
            current = MediaTransform(),
            tap = MediaTranslation(0f, 500f),
            viewport = viewport,
            fittedMedia = fittedMedia
        )
        val reset = doubleTapMediaTransform(
            current = zoomed,
            tap = MediaTranslation(0f, 500f),
            viewport = viewport,
            fittedMedia = fittedMedia
        )

        assertEquals(DOUBLE_TAP_MEDIA_ZOOM, zoomed.scale, 0f)
        assertEquals(MediaTranslation(500f, 0f), zoomed.translation)
        assertEquals(MediaTransform(), reset)
    }

    @Test
    fun initialPage_isAlwaysInsidePagerBounds() {
        assertEquals(0, normalizedMediaIndex(-8, itemCount = 5))
        assertEquals(4, normalizedMediaIndex(90, itemCount = 5))
        assertEquals(0, normalizedMediaIndex(3, itemCount = 0))
    }

    @Test
    fun mediaTypes_includeGifAndVideoButExcludeViewOnceFromGallery() {
        assertTrue(isGalleryMediaType("gif"))
        assertTrue(isGalleryMediaType("image/gif"))
        assertTrue(isGalleryMediaType("video"))
        assertTrue(isGalleryMediaType("video/webm"))
        assertTrue(isVideoMediaType("view_once_video"))
        assertFalse(isGalleryMediaType("view_once_video"))
        assertFalse(isGalleryMediaType("audio"))
    }

    @Test
    fun loadedPreview_preventsBlockingSpinnerAndErrorWhileOriginalImproves() {
        assertFalse(
            shouldShowBlockingImageProgress(
                isOriginalLoading = true,
                isPreviewReady = true
            )
        )
        assertFalse(
            shouldShowBlockingImageError(
                hasOriginalError = true,
                isPreviewReady = true
            )
        )
        assertTrue(
            shouldShowBlockingImageProgress(
                isOriginalLoading = true,
                isPreviewReady = false
            )
        )
        assertTrue(
            shouldShowBlockingImageError(
                hasOriginalError = true,
                isPreviewReady = false
            )
        )
    }

    @Test
    fun viewerSelection_usesStableIdWhenUrlsAreDuplicated() {
        val duplicateUrl = "https://cdn.example.com/shared"
        val messages = listOf(
            mediaMessage(id = "image-old", type = "image", url = duplicateUrl),
            mediaMessage(id = "gif-selected", type = "gif", url = duplicateUrl),
            mediaMessage(id = "video-new", type = "video", url = "https://cdn.example.com/video.mp4"),
            mediaMessage(id = "deleted", type = "image", url = "https://cdn.example.com/deleted", deleted = true),
            Message(id = "text", content_type = "text", content = "hola")
        )

        val selection = buildMessageMediaViewerSelection(
            messages = messages,
            selectedMessageId = "gif-selected",
            reverseOrder = true
        )

        assertNotNull(selection)
        assertEquals(listOf("video-new", "gif-selected", "image-old"), selection!!.items.map { it.id })
        assertEquals(1, selection.initialIndex)
        assertEquals("gif", selection.items[selection.initialIndex].type)
    }

    @Test
    fun plusOverlay_keepsEveryMediaItemAndCoversTheFourthTile() {
        val messages = (0 until 8).map { index ->
            mediaMessage(
                id = "media-$index",
                type = when (index % 3) {
                    0 -> "image"
                    1 -> "gif"
                    else -> "video"
                },
                url = "https://cdn.example.com/$index"
            )
        }

        val selection = buildMessageMediaViewerSelection(
            messages = messages,
            selectedMessageId = "media-3",
            reverseOrder = false
        )

        assertEquals(4, mediaGridOverlayCount(messages.size))
        assertEquals(1, mediaGridOverlayCount(5))
        assertEquals(0, mediaGridOverlayCount(4))
        assertNotNull(selection)
        assertEquals(8, selection!!.items.size)
        assertEquals(3, selection.initialIndex)
        assertEquals("media-3", selection.items[selection.initialIndex].id)
        assertTrue(selection.items.any { it.type == "gif" })
        assertTrue(selection.items.any { it.type == "video" })
    }

    @Test
    fun selectionReturnsNullWhenClickedMessageIsNotGalleryMedia() {
        val messages = listOf(Message(id = "text", content_type = "text", content = "hola"))

        assertNull(
            buildMessageMediaViewerSelection(
                messages = messages,
                selectedMessageId = "text",
                reverseOrder = false
            )
        )
    }

    @Test
    fun clusterEligibility_acceptsGalleryMediaAndPreservesHumanCaption() {
        assertTrue(isClusterableMediaGridMessage(mediaMessage("gif", "gif", "https://cdn/gif")))
        assertTrue(isClusterableMediaGridMessage(mediaMessage("video", "video", "https://cdn/video")))
        val captioned = mediaMessage("caption", "image", "https://cdn/image", content = "Mira")
        assertTrue(isClusterableMediaGridMessage(captioned))
        assertEquals("Mira", mediaGridCaption(listOf(captioned)))
        assertEquals("", mediaGridCaption(listOf(mediaMessage("default", "image", "https://cdn/default", content = "🖼️ Imagen"))))
        assertFalse(isClusterableMediaGridMessage(mediaMessage("sticker", "sticker", "https://cdn/sticker")))
    }

    @Test
    fun mediaCluster_groupsAdjacentMediaAndSkipsContinuationRows() {
        val messages = listOf(
            mediaMessage("first", "image", "https://cdn/1").copy(sender_id = "sender", created_at = "2026-07-21T12:00:00Z"),
            mediaMessage("second", "image", "https://cdn/2").copy(sender_id = "sender", created_at = "2026-07-21T12:00:10Z"),
            mediaMessage("third", "image", "https://cdn/3").copy(sender_id = "other", created_at = "2026-07-21T12:00:20Z")
        )

        assertEquals(listOf("first", "second"), mediaGridClusterAt(messages, 0)?.map { it.id })
        assertNull(mediaGridClusterAt(messages, 1))
        assertEquals(listOf("third"), mediaGridClusterAt(messages, 2)?.map { it.id })
    }

    private fun mediaMessage(
        id: String,
        type: String,
        url: String,
        content: String = "",
        deleted: Boolean = false
    ): Message {
        return Message(
            id = id,
            content = content,
            content_type = type,
            media_url = url,
            deleted_for_all = deleted
        )
    }
}
