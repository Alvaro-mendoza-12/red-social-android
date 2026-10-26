package conectate.com.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadGuardTest {

    private val guard = MediaUploadGuard()

    @Test
    fun `accepts actual Ogg Opus music and both compatible MIME names`() {
        val bytes = "OggS\u0000\u0002OpusHead".encodeToByteArray()
        listOf("audio/opus", "audio/ogg", "application/ogg").forEach { mime ->
            assertTrue(mime, guard.validate("community-music", "user/audio.ogg", bytes, mime).allowed)
        }
    }

    @Test
    fun `ADTS AAC music keeps its actual type and rejects an mp3 declaration`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 1, 0x7F, 0xFC.toByte())
        assertTrue(guard.validate("community-music", "user/audio.aac", bytes, "audio/aac").allowed)
        assertFalse(guard.validate("community-music", "user/audio.mp3", bytes, "audio/mpeg").allowed)
    }

    @Test
    fun `truncated container metadata returns a validation result`() {
        val bytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)
        guard.validate("community-music", "user/audio.m4a", bytes, "audio/mp4")
    }

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
    fun `accepts legacy and modern Excel attachments shared with generic MIME`() {
        val legacyExcel = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(
            guard.validate(
                "chat-media",
                "chat/report.xls",
                legacyExcel,
                "application/octet-stream"
            ).allowed
        )

        val modernExcel = byteArrayOf(
            0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        assertTrue(
            guard.validate(
                "chat-media",
                "chat/report.xlsx",
                modernExcel,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ).allowed
        )
        assertFalse(
            guard.validate(
                "chat-media",
                "chat/report.xls",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00),
                "application/vnd.ms-excel"
            ).allowed
        )
    }

    @Test
    fun `does not block zip-based workbook mislabeled as legacy xls before server inspection`() {
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0, 0, 0, 0, 0)
        val result = guard.validateMetadata(
            bucketName = "chat-media",
            fileName = "chat/report.xls",
            sizeBytes = 1024,
            contentType = "application/vnd.ms-excel",
            headerBytes = zipHeader,
        )
        assertTrue(result.allowed)
    }

    @Test
    fun `accepts unknown binary attachments while blocking active web files`() {
        val unknownBinary = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        assertTrue(
            guard.validate(
                "chat-media",
                "chat/design.psd",
                unknownBinary,
                "application/octet-stream"
            ).allowed
        )
        assertFalse(
            guard.validate(
                "chat-media",
                "chat/page.html",
                unknownBinary,
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
    fun `accepts generic mp4 container for an aac voice message`() {
        val header = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)
        listOf("chat-media", "group-audio", "community-audio").forEach { bucket ->
            assertTrue(guard.validateMetadata(bucket, "owner/voice.m4a", 1024, "audio/mp4", header).allowed)
        }
    }

    @Test
    fun `profile music accepts flac and enforces its size limit`() {
        val flacHeader = byteArrayOf(
            0x66, 0x4C, 0x61, 0x43, 0x00, 0x00, 0x00, 0x22,
            0x00, 0x00, 0x00, 0x00
        )
        assertTrue(
            guard.validateMetadata(
                bucketName = "profile-audio",
                fileName = "user/song.flac",
                sizeBytes = 8L * 1024 * 1024,
                contentType = "audio/flac",
                headerBytes = flacHeader
            ).allowed
        )
        assertFalse(
            guard.validateMetadata(
                bucketName = "profile-audio",
                fileName = "user/song.flac",
                sizeBytes = 40L * 1024 * 1024 + 1,
                contentType = "audio/flac",
                headerBytes = flacHeader
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
