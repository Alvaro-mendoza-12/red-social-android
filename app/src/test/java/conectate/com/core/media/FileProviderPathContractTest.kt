package conectate.com.core.media

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileProviderPathContractTest {

    @Test
    fun providerExposesOnlyDedicatedShareDirectories() {
        val xml = fileCandidates().first(File::isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val nodes = document.documentElement.childNodes
        val paths = buildSet {
            for (index in 0 until nodes.length) {
                val attributes = nodes.item(index).attributes ?: continue
                val path = attributes.getNamedItem("path")?.nodeValue ?: continue
                assertFalse("FileProvider must never expose an entire root", path == "." || path == "./")
                add(path)
            }
        }

        assertEquals(
            setOf(
                "file-provider/crops/",
                "file-provider/chat-captures/",
                "file-provider/media-exports/",
                "file-provider/challenges/",
                "file-provider/moment-captures/",
                "file-provider/recovery-evidence/"
            ),
            paths
        )
    }

    private fun fileCandidates(): List<File> = listOf(
        File("src/main/res/xml/file_paths.xml"),
        File("app/src/main/res/xml/file_paths.xml")
    )
}
