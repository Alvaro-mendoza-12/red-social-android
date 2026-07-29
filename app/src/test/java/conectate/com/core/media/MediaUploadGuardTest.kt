package conectate.com.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadGuardTest {

    private val guard = MediaUploadGuard()

    @Test
    fun `accepts valid jpeg payload`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = guard.validate(
            bucketName = "avatars",
            fileName = "user/photo.jpg",
            bytes = bytes,
            contentType = "image/jpeg"
        )

        assertTrue(result.allowed)
    }

    @Test
    fun `rejects executable signatures`() {
        val bytes = byteArrayOf(0x4D, 0x5A, 0x11, 0x22, 0x33, 0x44)

        val result = guard.validate(
            bucketName = "chat-media",
            fileName = "chat/file.mp3",
            bytes = bytes,
            contentType = "audio/mpeg"
        )

        assertFalse(result.allowed)
    }

    @Test
    fun `rejects extension not allowed by bucket policy`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = guard.validate(
            bucketName = "avatars",
            fileName = "user/photo.exe",
            bytes = bytes,
            contentType = "image/jpeg"
        )

        assertFalse(result.allowed)
    }

    @Test
    fun `stream metadata accepts mp4 without loading the full video`() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6F, 0x6D
        )

        val result = guard.validateMetadata(
            bucketName = "videos",
            fileName = "user/video.mp4",
            sizeBytes = 32L * 1024 * 1024,
            contentType = "video/mp4",
            headerBytes = header
        )

        assertTrue(result.allowed)
    }

    @Test
    fun `stream metadata accepts short high bitrate source above old 100 MB limit`() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6F, 0x6D
        )

        val result = guard.validateMetadata(
            bucketName = "videos",
            fileName = "user/video.mp4",
            sizeBytes = 121L * 1024 * 1024,
            contentType = "video/mp4",
            headerBytes = header
        )

        assertTrue(result.allowed)
    }

    @Test
    fun `live replay accepts owned mp4 up to replay limit`() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x69, 0x73, 0x6F, 0x6D
        )

        assertTrue(
            guard.validateMetadata(
                bucketName = "live-replays",
                fileName = "user/replays/live/replay.mp4",
                sizeBytes = 256L * 1024 * 1024,
                contentType = "video/mp4",
                headerBytes = header
            ).allowed
        )
        assertFalse(
            guard.validateMetadata(
                bucketName = "live-replays",
                fileName = "user/replays/live/replay.mp4",
                sizeBytes = 512L * 1024 * 1024 + 1,
                contentType = "video/mp4",
                headerBytes = header
            ).allowed
        )
    }

    @Test
    fun `accepts gif and common document signatures in messaging buckets`() {
        val gif = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(guard.validate("chat-media", "chat/sticker.gif", gif, "image/gif").allowed)

        val pdf = "%PDF-1.7\nhello".encodeToByteArray()
        assertTrue(
            guard.validate(
                "group-media",
                "group/document.pdf",
                pdf,
                "application/octet-stream"
            ).allowed
        )

        val docx = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        assertTrue(
            guard.validate(
                "community-media",
                "community/document.docx",
                docx,
                "application/octet-stream"
            ).allowed
        )
    }

    @Test
    fun `accepts m4a signature for group audio`() {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x4D, 0x34, 0x41, 0x20
        )
        assertTrue(
            guard.validateMetadata(
                bucketName = "group-audio",
                fileName = "group/voice.m4a",
                sizeBytes = 1024,
                contentType = "audio/mp4",
                headerBytes = header
            ).allowed
        )
    }

    @Test
    fun `report evidence accepts real png and rejects oversized payloads`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00
        )
        assertTrue(
            guard.validateMetadata(
                bucketName = "report-evidence",
                fileName = "reports/user/evidence.png",
                sizeBytes = 1024,
                contentType = "image/png",
                headerBytes = pngHeader
            ).allowed
        )
        assertFalse(
            guard.validateMetadata(
                bucketName = "report-evidence",
                fileName = "reports/user/evidence.png",
                sizeBytes = 10L * 1024 * 1024 + 1,
                contentType = "image/png",
                headerBytes = pngHeader
            ).allowed
        )
    }
}
