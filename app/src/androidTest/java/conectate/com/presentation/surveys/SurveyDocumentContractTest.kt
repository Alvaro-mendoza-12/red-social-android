package conectate.com.presentation.surveys

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveyDocumentContractTest {
    @Test fun documentPickerReceivesCorrectMimeAndNoResponseData() {
        val contract = SurveyDocumentContract()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mime in listOf("application/pdf", "text/csv;charset=utf-8", "image/png",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            val intent = contract.createIntent(context, JSONObject().put("mime", mime).put("name", "carpeta/encuesta.xlsx").put("data", "datos-privados"))
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(mime.substringBefore(';'), intent.type)
            assertEquals("carpetaencuesta.xlsx", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(setOf(Intent.EXTRA_TITLE), intent.extras!!.keySet())
        }
        val uri = Uri.parse("content://provider/encuesta")
        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
    }
}
