package conectate.com.presentation.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditorEntityIdsTest {
    @Test fun rapidEditsCannotReuseAClipIdentity() {
        val ids = List(10_000) { nextEditorEntityId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun newEditsSkipIdentitiesRestoredFromAnExistingProject() {
        val first = nextEditorEntityId()
        val restored = (first + 1..first + 100_000).toSet()
        assertFalse(nextEditorEntityId(restored) in restored)
    }
}
