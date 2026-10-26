package conectate.com.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMimeResolverTest {
    @Test fun aacAdtsIsNotMislabeledAsMp3OrGivenAnM4aSuffix() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 1, 0x7F, 0xFC.toByte())
        val meta = resolveMediaMeta("audio", "audio/mp4", "audio/mp4", inferMimeTypeFromBytes(bytes))
        assertEquals("audio/aac", meta.mimeType)
        assertEquals(".aac", meta.extensionWithDot)
    }

    @Test fun editedImageBytesOverrideTheOriginalProviderMime() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        val meta = resolveMediaMeta("image", "image/png", "image/png", inferMimeTypeFromBytes(bytes))
        assertEquals("image/jpeg", meta.mimeType)
        assertEquals(".jpg", meta.extensionWithDot)
    }

    @Test fun truncatedFtypHeaderDoesNotThrow() {
        assertEquals(null, inferMimeTypeFromBytes(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
    }

    @Test fun androidAacRecordingWithAnIsomContainerRemainsAudio() {
        val header = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)
        val meta = resolveMediaMeta("audio", null, "audio/mp4", inferMimeTypeFromBytes(header))
        assertEquals("audio/mp4", meta.mimeType)
        assertEquals(".m4a", meta.extensionWithDot)
    }

    @Test fun retryOfAnOlderVoiceFileWithAMp4ExtensionRemainsAudio() {
        val meta = resolveMediaMeta("audio", "video/mp4", "video/mp4", "video/mp4")
        assertEquals("audio/mp4", meta.mimeType)
        assertEquals(".m4a", meta.extensionWithDot)
    }

    @Test fun aVideoWithTheSameGenericContainerRemainsVideo() {
        val meta = resolveMediaMeta("video", "video/mp4", "video/mp4", "video/mp4")
        assertEquals("video/mp4", meta.mimeType)
        assertEquals(".mp4", meta.extensionWithDot)
    }

    @Test fun aSpecificAudioSignatureStillOverridesMisleadingMetadata() {
        assertEquals("audio/wav", resolveMediaMeta("audio", "audio/mp4", "audio/mp4", "audio/wav").mimeType)
    }

    @Test fun aM4aBrandIsRecognizedAsAudio() {
        val header = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 77, 52, 65, 32)
        assertEquals("audio/mp4", inferMimeTypeFromBytes(header))
    }

    @Test fun genericSharedMimeUsesTheOriginalExcelFileName() {
        val meta = resolveMediaMeta(
            preferredMediaType = "document",
            declaredMime = "application/octet-stream",
            guessedMime = guessMimeTypeFromFileName("Research results.xls"),
            inferredMime = null,
            fallbackExtension = "xls"
        )
        assertEquals("application/vnd.ms-excel", meta.mimeType)
        assertEquals(".xls", meta.extensionWithDot)
    }

    @Test fun filenameMimeResolverRecognizesModernExcel() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            guessMimeTypeFromFileName("Research results.xlsx")
        )
    }

    @Test fun unknownImageLikeDocumentMimeIsStoredAsOpaqueFile() {
        val meta = resolveMediaMeta(
            preferredMediaType = "document",
            declaredMime = "image/vnd.adobe.photoshop",
            guessedMime = "image/vnd.adobe.photoshop",
            inferredMime = null,
            fallbackExtension = "psd"
        )
        assertEquals("application/octet-stream", meta.mimeType)
        assertEquals(".psd", meta.extensionWithDot)
    }
}
