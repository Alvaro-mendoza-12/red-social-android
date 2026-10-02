package conectate.com.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentMessageLabelTest {
    @Test fun attachedDocumentUsesItsRealNameInsteadOfTheGenericCaption() {
        assertEquals("Novvin-QA.txt", documentMessageLabel("📎 Archivo", "Novvin-QA.txt"))
        assertEquals("Novvin-QA.txt", documentMessageLabel("", "Novvin-QA.txt"))
    }
    @Test fun anExplicitCommentIsPreserved() {
        assertEquals("Here is the report", documentMessageLabel(" Here is the report ", "report.pdf"))
    }
    @Test fun theLabelRemovesPathsAndControlCharacters() {
        assertEquals("report.pdf", documentMessageLabel("", "folder\\report\u0000.pdf"))
    }
    @Test fun missingNameHasAnUnderstandableFallback() {
        assertEquals("Archivo adjunto", documentMessageLabel("", null))
        assertEquals("Archivo adjunto", documentMessageLabel("", ".."))
    }
}
