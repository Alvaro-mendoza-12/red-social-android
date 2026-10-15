package conectate.com.presentation.videos

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.roundToInt

internal const val MAX_CUBE_BYTES = 2 * 1024 * 1024

internal data class CubeColorLut(val title: String, val colors: Array<Array<IntArray>>)

/** SDR .cube input, red changes fastest; Media3 indexes colors[R][G][B]. */
internal fun parseCubeColorLut(bytes: ByteArray): CubeColorLut {
    require(bytes.size <= MAX_CUBE_BYTES) { "El filtro supera 2 MB." }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    var title = "Filtro importado"
    var colors: Array<Array<IntArray>>? = null
    var count = 0
    var minSeen = false
    var maxSeen = false
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        require(line.length <= 512) { "El archivo contiene una línea demasiado larga." }
        val parts = line.split(Regex("\\s+"))
        when (parts.first()) {
            "TITLE" -> {
                require(count == 0) { "La cabecera debe preceder a los colores." }
                title = line.removePrefix("TITLE").trim().trim('"').filterNot { it.isISOControl() }.take(60)
                    .ifBlank { "Filtro importado" }
            }
            "LUT_3D_SIZE" -> {
                require(colors == null && parts.size == 2) { "El tamaño del filtro no es válido." }
                val size = parts[1].toIntOrNull()
                require(size != null && size in 2..33) { "Usa un filtro 3D de 2 a 33 puntos." }
                colors = Array(size) { Array(size) { IntArray(size) } }
            }
            "DOMAIN_MIN", "DOMAIN_MAX" -> {
                require(count == 0 && parts.size == 4) { "El dominio del filtro no es válido." }
                val expected = if (parts.first() == "DOMAIN_MIN") 0f else 1f
                require(parts.drop(1).all { it.toFloatOrNull() == expected }) { "Usa un filtro SDR con dominio de 0 a 1." }
                if (expected == 0f) { require(!minSeen) { "DOMAIN_MIN está repetido." }; minSeen = true }
                else { require(!maxSeen) { "DOMAIN_MAX está repetido." }; maxSeen = true }
            }
            else -> {
                val cube = colors ?: error("Falta LUT_3D_SIZE; usa un archivo .cube 3D.")
                val size = cube.size
                require(parts.size == 3 && count < size * size * size) { "El filtro contiene colores de más o datos incompatibles." }
                val rgb = parts.map { it.toFloatOrNull() ?: error("El filtro contiene datos incompatibles.") }
                require(rgb.all { it.isFinite() && it in 0f..1f }) { "Usa colores SDR entre 0 y 1." }
                cube[count % size][(count / size) % size][count / (size * size)] =
                    (0xff shl 24) or ((rgb[0] * 255).roundToInt() shl 16) or
                        ((rgb[1] * 255).roundToInt() shl 8) or (rgb[2] * 255).roundToInt()
                count++
            }
        }
    }
    val cube = colors ?: error("Falta LUT_3D_SIZE; usa un archivo .cube 3D.")
    require(count == cube.size * cube.size * cube.size) { "El filtro está incompleto." }
    return CubeColorLut(title, cube)
}

/** Copies validated bytes into app storage; the project never relies on a temporary picker grant. */
internal class CubeColorLutStore(private val context: Context) {
    private val directory get() = java.io.File(context.filesDir, "editor-color-luts")

    fun import(uri: Uri): Pair<String, CubeColorLut> {
        require(uri.scheme == "content") { "Selecciona un archivo del dispositivo." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_CUBE_BYTES) { "El filtro supera 2 MB." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("No se pudo abrir el filtro.")
        val parsed = parseCubeColorLut(bytes)
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        directory.mkdirs()
        val file = java.io.File(directory, "$id.cube")
        if (!file.exists()) {
            require(directory.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 16L * 1024 * 1024) {
                "El almacenamiento de filtros llegó a 16 MB. Libera espacio antes de importar otro."
            }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
        return id to parsed
    }

    fun load(id: String): CubeColorLut {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "El identificador del filtro no es válido." }
        val file = java.io.File(directory, "$id.cube")
        require(file.isFile && file.length() <= MAX_CUBE_BYTES) { "No se encontró el filtro guardado. Vuelve a importarlo." }
        return parseCubeColorLut(file.inputStream().use { it.readMediaBytesWithLimit(MAX_CUBE_BYTES.toLong()) })
    }

    fun discardUnused(preservedIds: Set<String>): Int = directory.listFiles().orEmpty().count { file ->
        file.name.matches(Regex("[a-f0-9]{64}\\.cube")) && file.name.removeSuffix(".cube") !in preservedIds &&
            isDirectChildOfDirectory(directory, file) && file.delete()
    }
}
