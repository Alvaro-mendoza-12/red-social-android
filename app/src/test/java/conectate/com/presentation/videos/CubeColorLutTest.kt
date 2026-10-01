package conectate.com.presentation.videos

import org.junit.Assert.*
import org.junit.Test

class CubeColorLutTest {
    private val identity = """
        # File exported by a color editor
        TITLE "Identidad"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()

    @Test fun indexesRedGreenBlueWithoutReversingTheCube() {
        val lut = parseCubeColorLut(identity.toByteArray())
        assertEquals("Identidad", lut.title)
        assertEquals(0xffff0000.toInt(), lut.colors[1][0][0])
        assertEquals(0xff00ff00.toInt(), lut.colors[0][1][0])
        assertEquals(0xff0000ff.toInt(), lut.colors[0][0][1])
    }

    @Test fun rejectsMalformedAndUnsupportedInputs() {
        listOf(identity.replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 65"),
            identity.replace("1 1 1", "NaN 1 1"), identity.replace("DOMAIN_MAX 1 1 1", "DOMAIN_MAX 2 2 2"),
            identity.substringBeforeLast("1 1 1"), "LUT_1D_SIZE 2\n0 0 0\n1 1 1",
            identity + "\n1 1 1", identity + "\nLUT_3D_SIZE 2").forEach {
            assertTrue(runCatching { parseCubeColorLut(it.toByteArray()) }.isFailure)
        }
        assertTrue(runCatching { parseCubeColorLut(ByteArray(MAX_CUBE_BYTES + 1)) }.isFailure)
    }

    @Test fun importedColorsAlwaysRequireARealRender() {
        val clip = EditorClip(1, durationMs = 2000, customLutId = "a".repeat(64))
        assertFalse(clip.isLosslessTrimConfiguration())
    }
}
