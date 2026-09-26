package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class MomentDownloadPolicyTest {
    private val mp4 = momentExportTarget("id", "https://cdn.test/clip.mp4", "video")

    @Test fun originalContainerDeterminesDocumentMimeAndExtension() {
        val webm = momentExportTarget("legacy/id", "https://cdn.test/clip.WEBM?token=private", "video")
        assertEquals("video/webm", webm.mimeType)
        assertEquals("Novvin_Momento_legacyid.webm", webm.fileName)
        val mov = momentExportTarget("id", "https://cdn.test/clip.mov", "image")
        assertEquals("video/quicktime", mov.mimeType)
        assertTrue(mov.fileName.endsWith(".mov"))
    }

    @Test fun explicitVideoMimeWorksForExtensionlessSignedObject() {
        val target = momentExportTarget("id", "https://cdn.test/object?token=secret", "video/webm")
        assertEquals("video/webm", target.mimeType)
        assertTrue(target.fileName.endsWith(".webm"))
    }

    @Test fun unknownContainerIsNeverRenamedToMp4() {
        val target = momentExportTarget("id", "https://cdn.test/object", "video")
        assertEquals("application/octet-stream", target.mimeType)
        assertFalse(target.fileName.contains(".mp4"))
    }

    @Test fun rejectsHtmlAndJsonSuccessBodiesBeforeOpeningOutput() {
        listOf("text/html; charset=utf-8", "application/json").forEach { mime ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMomentExportResponse(mp4, mapOf("Content-Type" to listOf(mime)), 100)
            }
        }
    }

    @Test fun headersAreCaseInsensitiveAndTruncatedBodyCannotBeSuccessful() {
        val expected = validateMomentExportResponse(mp4, mapOf("content-type" to listOf("video/mp4"), "content-length" to listOf("32")), 100)
        assertEquals(32L, expected)
        assertThrows(IllegalArgumentException::class.java) { validateMomentExportSize(16, expected) }
        validateMomentExportSize(32, expected)
    }

    @Test fun rejectsPartialAndOversizedResponsesBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMomentExportResponse(mp4, mapOf("Content-Range" to listOf("bytes 0-10/100")), 100)
        }
        listOf("0", "101", "-1", "invalid").forEach { length ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMomentExportResponse(mp4, mapOf("Content-Length" to listOf(length)), 100)
            }
        }
    }

    @Test fun compressedLengthIsNotComparedWithDecodedBytes() {
        val expected = validateMomentExportResponse(mp4, mapOf("Content-Encoding" to listOf("gzip"), "Content-Length" to listOf("20")), 100)
        assertNull(expected)
        validateMomentExportSize(60, expected)
    }

    @Test fun conflictingContainerFailsInsteadOfSavingMislabeledOriginal() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMomentExportResponse(mp4, mapOf("Content-Type" to listOf("video/webm")), 100)
        }
        val m4v = momentExportTarget("id", "https://cdn.test/a.m4v", "video")
        validateMomentExportResponse(m4v, mapOf("Content-Type" to listOf("video/mp4")), 100)
        validateMomentExportResponse(mp4, mapOf("Content-Type" to listOf("application/octet-stream")), 100)
    }
}
