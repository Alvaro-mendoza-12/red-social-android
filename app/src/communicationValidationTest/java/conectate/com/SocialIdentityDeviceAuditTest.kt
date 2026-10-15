package conectate.com

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import conectate.com.data.model.AuthorFollowState
import conectate.com.data.model.DirectMessageRequestState
import conectate.com.data.model.Moment
import conectate.com.data.model.Post
import conectate.com.presentation.social.toMomentMusicSelection
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.PostCard
import conectate.com.presentation.messages.DirectMessageRequestNotice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SocialIdentityDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "communication-qa")
        check(folder.mkdirs() || folder.isDirectory)
        File(folder, name).outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }
    @Test fun sixSecondMomentCanSeekAnywhereInTheOriginalSong() {
        val clip = conectate.com.presentation.components.PreparedAudioClip(android.net.Uri.parse("file:///source.m4a"), "Song", durationMs = 180_000L)
        val selection = clip.toMomentMusicSelection()
        assertEquals(180_000L, selection.track.durationMs)
        assertEquals(6_000L, selection.endMs - selection.startMs)
        val start = androidx.compose.runtime.mutableLongStateOf(0L)
        compose.setContent { MaterialTheme {
            conectate.com.presentation.social.MomentMusicExcerptTimeline(180_000L, start.longValue, start.longValue + 6_000L, 0L) { start.longValue = it }
        } }
        compose.onNodeWithTag("moment_music_full_track_seek").performSemanticsAction(SemanticsActions.SetProgress) { it(120_000f) }
        compose.runOnIdle { assertEquals(120_000L, start.longValue) }
        compose.onNodeWithText("2:00 → 2:06").assertIsDisplayed()
        compose.onNodeWithText("Canción: 3:00").assertIsDisplayed()
        screenshot("social-music-full-source-seek.png")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "audit-music-source-${java.util.UUID.randomUUID()}.wav")
        var exported: File? = null
        try {
            createFullSongFixture(source)
            val output = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(60_000L) {
                    conectate.com.presentation.components.exportAudioClip(context, android.net.Uri.fromFile(source), 120_000L, 126_000L).getOrThrow()
                }
            }
            exported = File(requireNotNull(output.path))
            assertEquals(context.cacheDir.canonicalFile, exported.parentFile?.canonicalFile)
            val retriever = android.media.MediaMetadataRetriever()
            val duration = try { retriever.setDataSource(exported.absolutePath); requireNotNull(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() }
                finally { retriever.release() }
            assertTrue("Export must be six seconds", duration in 5_750L..6_250L)
            val frequency = decodedFixtureFrequency(exported)
            // Source starts at 200Hz; after 120s it is 1000Hz. A crop that
            // silently returns the first six seconds cannot pass this check.
            assertTrue("Export must contain audio after two minutes: $frequency Hz", frequency in 750.0..1300.0)
        } finally {
            exported?.let { assertTrue(!it.exists() || it.delete()) }
            assertTrue(!source.exists() || source.delete())
        }
    }

    private fun createFullSongFixture(file: File) {
        val rate = 16_000; val samples = rate * 180; val bytes = samples * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        header.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(bytes)
        file.outputStream().buffered().use { output ->
            output.write(header.array())
            val block = java.nio.ByteBuffer.allocate(32_768).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until samples) {
                val frequency = if (index >= rate * 120) 1_000.0 else 200.0
                val value = (kotlin.math.sin(2.0 * Math.PI * frequency * index / rate) * 12_000).toInt().toShort()
                block.putShort(value)
                if (!block.hasRemaining()) { output.write(block.array()); block.clear() }
            }
            if (block.position() > 0) output.write(block.array(), 0, block.position())
        }
    }

    private fun decodedFixtureFrequency(file: File): Double {
        val extractor = android.media.MediaExtractor(); var decoder: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            decoder = android.media.MediaCodec.createDecoderByType(requireNotNull(format.getString(android.media.MediaFormat.KEY_MIME)))
            decoder.configure(format, null, null, 0); decoder.start()
            val info = android.media.MediaCodec.BufferInfo(); var inputEnded = false; var previous = 0; var crossings = 0; var samples = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000L
            while (samples < rate / 4 && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(5_000L)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index)); input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 5_000L)
                if (index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat; rate = decoded.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    assertEquals(1, decoded.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                    if (decoded.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, decoded.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING))
                } else if (index >= 0) {
                    val buffer = requireNotNull(decoder.getOutputBuffer(index)).duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    while (buffer.remaining() >= 2 && samples < rate / 4) {
                        val value = buffer.short.toInt()
                        if (samples > 0 && ((previous < 0 && value >= 0) || (previous >= 0 && value < 0))) crossings++
                        previous = value; samples++
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            assertTrue("Decoder produced too few actual PCM samples", samples >= rate / 5)
            return crossings * rate.toDouble() / (2.0 * samples)
        } finally { decoder?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }

    @Test fun notificationAndMomentRingUseTheCurrentNovvinBrand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val drawable = requireNotNull(androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_notification))
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96);drawable.draw(android.graphics.Canvas(bitmap))
        assertTrue("The Novvin V must reach the lower centre", android.graphics.Color.alpha(bitmap.getPixel(48, 80)) > 0)
        bitmap.recycle()
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Row {
                CircularProfileImage("", fallbackName="Novvin", momentViewed=false)
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_notification), "Icono Novvin")
            }
        } }
        compose.onNodeWithContentDescription("Icono Novvin").assertIsDisplayed()
        screenshot("social-brand-ring-notification.png")
    }

    @Test fun firstMessageWarningMustBeAcknowledged() {
        val acknowledged = mutableStateOf(false)
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("new"), "Sebas", acknowledged.value, false,
            onContinue = { acknowledged.value = true }, onAccept = {}, onReject = {}, onRefresh = {}) } }
        compose.onNodeWithText("Puedes enviar un solo mensaje.", substring = true).assertIsDisplayed()
        screenshot("social-request-warning.png")
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Continuar").assertDoesNotExist()
    }
    @Test fun pendingSenderOnlyCanRefresh() {
        var refreshes = 0
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("pending_outgoing"), "Sebas", true, false,
            onContinue = {}, onAccept = {}, onReject = {}, onRefresh = { refreshes++ }) } }
        compose.onNodeWithText("Aceptar").assertDoesNotExist()
        compose.onNodeWithText("Continuar").assertDoesNotExist()
        screenshot("social-request-pending.png")
        compose.onNodeWithText("Actualizar").performClick()
        assertEquals(1, refreshes)
    }
    @Test fun recipientCanAcceptOrRejectWithTheSenderNameVisible() {
        var accepted = 0; var rejected = 0
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("pending_incoming"), "Sebas", false, false,
            onContinue = {}, onAccept = { accepted++ }, onReject = { rejected++ }, onRefresh = {}) } }
        compose.onNodeWithText("Sebas te envió una solicitud de mensaje").assertIsDisplayed()
        screenshot("social-request-incoming.png")
        compose.onNodeWithText("Aceptar").performClick(); compose.onNodeWithText("Rechazar").performClick()
        assertEquals(1, accepted); assertEquals(1, rejected)
    }
    @Test fun aDecisionInProgressDisablesBothActions() {
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("pending_incoming"), "Sebas", false, true,
            onContinue = {}, onAccept = {}, onReject = {}, onRefresh = {}) } }
        compose.onNodeWithText("Aceptar").assertIsNotEnabled();compose.onNodeWithText("Rechazar").assertIsNotEnabled()
    }
    @Test fun avatarExposesViewedAndUnviewedMomentStates() {
        val viewed = mutableStateOf(false)
        compose.setContent { MaterialTheme { CircularProfileImage("", fallbackName="Sebas", momentViewed=viewed.value) } }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Momento sin ver")).assertExists()
        compose.runOnIdle { viewed.value = true }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Momento visto")).assertExists()
    }
    @Test fun profileCountsWrapWithoutOverlapWithLargeText() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)) {
                MaterialTheme { Box(Modifier.width(180.dp)) {
                    conectate.com.presentation.profile.ProfileFollowCounts(123456, 678901, {}, {})
                } }
            }
        }
        val followers = compose.onNodeWithContentDescription("123456 Seguidores").fetchSemanticsNode().boundsInRoot
        val following = compose.onNodeWithContentDescription("678901 Siguiendo").fetchSemanticsNode().boundsInRoot
        assertTrue(followers.right <= following.left || following.top >= followers.bottom)
        compose.onNodeWithContentDescription("123456 Seguidores").assertIsDisplayed()
        compose.onNodeWithContentDescription("678901 Siguiendo").assertIsDisplayed()
        compose.onNodeWithText("Publicaciones").assertDoesNotExist()
        for (label in listOf("Seguidores", "Siguiendo")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("The label must remain readable without a broken word: $label", 1, layouts.single().lineCount)
        }
        screenshot("social-profile-counts-large-font.png")
    }
    @Test fun feedFollowButtonAndAvatarHaveSeparateActions() {
        var followClicks=0;var profileClicks=0;var openedMoment=""
        val post=Post(userId="author",userName="Sebas",createdAt="2026-10-10T22:00:00Z")
        val moment=Moment(id="moment",userId="author",mediaUrl="",mediaType="image",expiresAt="2026-10-11T22:00:00Z")
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) { PostCard(post=post,onLikeClick={},onReactionSelect={},onReactionSummaryClick={},onCommentClick={},onShareClick={},
            onUserClick={profileClicks++},authorFollowState=AuthorFollowState.NOT_FOLLOWING,onFollowClick={followClicks++},
            authorMoment=moment,onMomentClick={openedMoment=it}) } } }
        screenshot("social-feed-follow-moment.png")
        compose.onNodeWithText("Seguir").performClick()
        assertEquals(1,followClicks);assertEquals(0,profileClicks)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Momento sin ver")).performClick()
        assertEquals("moment",openedMoment);assertEquals(0,profileClicks)
        compose.onNodeWithText("Sebas").performClick();assertEquals(1,profileClicks)
    }
}
