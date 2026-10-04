package conectate.com.data.music

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

const val MAX_MUSIC_LYRICS_BYTES = 48_003

/** Read at most the UTF-8 bound plus one rejection byte, never an unbounded file. */
fun readBoundedMusicLyrics(stream: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() <= MAX_MUSIC_LYRICS_BYTES) {
        val count = stream.read(buffer, 0, minOf(buffer.size, MAX_MUSIC_LYRICS_BYTES + 1 - output.size()))
        if (count < 0) break
        if (count == 0) {
            val next = stream.read()
            if (next < 0) break
            output.write(next)
        } else output.write(buffer, 0, count)
    }
    require(output.size() <= MAX_MUSIC_LYRICS_BYTES) { "La letra supera el límite de 12.000 caracteres." }
    return output.toByteArray()
}

/** Import real LRC source times and align them to the exact exported clip. */
fun importMusicLyrics(bytes: ByteArray, durationMs: Long, sourceStartMs: Long = 0L): String {
    require(bytes.size <= MAX_MUSIC_LYRICS_BYTES) { "La letra supera el límite de 12.000 caracteres." }
    require(durationMs > 0 && sourceStartMs >= 0 && sourceStartMs <= Long.MAX_VALUE - durationMs) {
        "No se pudo validar la duración de la pista."
    }
    val raw = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("El archivo .lrc debe usar texto UTF-8.")
    }
    require(raw.length <= 12_000) { "La letra supera el límite de 12.000 caracteres." }
    val end = sourceStartMs + durationMs
    val selected = timedMusicLyrics(raw).filter { it.startMs < end && it.endMs > sourceStartMs }
    require(selected.any { it.text.isNotBlank() }) {
        "No hay letras con tiempos válidos dentro de esta pista o recorte. Elige el .lrc de esta canción."
    }
    val normalized = selected.flatMap { cue ->
        val time = (cue.startMs - sourceStartMs).coerceAtLeast(0L)
        val tag = String.format(Locale.ROOT, "[%02d:%02d.%03d]", time / 60_000, time / 1000 % 60, time % 1000)
        cue.text.split('\n').map { tag + it }
    }.joinToString("\n")
    require(normalized.length <= 12_000) { "La letra supera el límite de 12.000 caracteres." }
    return normalized
}
