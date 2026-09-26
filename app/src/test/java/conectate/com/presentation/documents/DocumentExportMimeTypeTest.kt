package conectate.com.presentation.documents

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentExportMimeTypeTest {
    @Test
    fun exportedPdfUsesPdfMimeTypeInTheAndroidSavePicker() {
        assertEquals("application/pdf", documentMimeType("informe-visual.pdf"))
        assertEquals("application/pdf", documentMimeType("INFORME.PDF"))
    }

    @Test
    fun originalOfficeDocumentKeepsItsFormatWhenSavingACopy() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            documentMimeType("reporte.docx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            documentMimeType("tabla.xlsx"),
        )
    }
}
