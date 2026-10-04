package conectate.com.data.music

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class MusicLyricsImportTest {
    @Test fun utf8BomAndRealOffsetAreAccepted() {
        val raw = "\uFEFF[offset:-250]\n[00:01.250]QA canción\n[00:03]QA next"
        val imported = importMusicLyrics(raw.toByteArray(Charsets.UTF_8), 6_000)
        assertEquals(listOf(1000L,2750L), timedMusicLyrics(imported, 6000).map { it.startMs })
        assertEquals("QA canción", timedMusicLyrics(imported).first().text)
    }
    @Test fun exactCropRebasesActiveLineAndPreservesBlankInstrumentalGap() {
        val raw = "[00:58]QA earlier\n[01:02]QA next\n[01:04]\n[01:05]QA final\n[01:06]Outside"
        val cues = timedMusicLyrics(importMusicLyrics(raw.toByteArray(), 6000, 60000), 6000)
        assertEquals(listOf(0L,2000L,4000L,5000L), cues.map { it.startMs })
        assertEquals(listOf("QA earlier","QA next","","QA final"), cues.map { it.text })
    }
    @Test fun invalidEncodingPlainTextAndMissingFragmentAreRejected() {
        for (bytes in listOf(byteArrayOf(0xC3.toByte(),0x28), "No timings".toByteArray(), "[00:20]Outside".toByteArray())) {
            assertThrows(IllegalArgumentException::class.java) { importMusicLyrics(bytes,6000) }
        }
        assertThrows(IllegalArgumentException::class.java) { importMusicLyrics("[00:00]QA".toByteArray(),0) }
        assertThrows(IllegalArgumentException::class.java) { importMusicLyrics("[00:00]QA".toByteArray(),6000,-1) }
    }
    @Test fun unicodeCharacterAndByteLimitsAreBothEnforced() {
        assertThrows(IllegalArgumentException::class.java) { importMusicLyrics(ByteArray(MAX_MUSIC_LYRICS_BYTES+1),6000) }
        assertThrows(IllegalArgumentException::class.java) { importMusicLyrics(("[00:00]"+"a".repeat(12000)).toByteArray(),6000) }
        val valid = importMusicLyrics(("[00:00]"+"é".repeat(200)).toByteArray(Charsets.UTF_8),6000)
        assertEquals("é".repeat(200),timedMusicLyrics(valid).first().text)
    }
    @Test fun boundedReaderConsumesOnlyOneExtraByteAndAcceptsNormalFile() {
        var consumed=0
        val stream=object: InputStream() { override fun read():Int { consumed++;return 65 } }
        assertThrows(IllegalArgumentException::class.java) { readBoundedMusicLyrics(stream) }
        assertEquals(MAX_MUSIC_LYRICS_BYTES+1,consumed)
        val raw="[00:00]QA".toByteArray()
        assertArrayEquals(raw,readBoundedMusicLyrics(ByteArrayInputStream(raw)))
    }
    @Test fun allBlankInstrumentalExcerptIsNotMistakenForAvailableLyrics() {
        val raw="[00:00]QA first\n[00:02]\n[00:10]QA next".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { importMusicLyrics(raw,6000,3000) }
    }
}
