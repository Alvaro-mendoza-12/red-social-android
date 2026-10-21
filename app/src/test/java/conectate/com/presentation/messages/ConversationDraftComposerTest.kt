package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class ConversationDraftComposerTest {
    @Test fun emptyNavigationSeedPreservesRestoredDraft() {
        var stored = "borrador restaurado"
        val composer = QueuedMessageComposer<String>()
        composer.bindDraft(stored) { stored = it }
        for (seed in listOf(null, "", " ", "\n")) {
            assertFalse(composer.applyInitialText(seed))
            assertEquals("borrador restaurado", composer.text.value)
            assertEquals("borrador restaurado", stored)
        }
        assertTrue(composer.applyInitialText("texto compartido"))
        assertEquals("texto compartido", stored)
    }
    @Test fun typingAndQueueAcceptancePersistButFailedSendsKeepDraft() {
        var stored = "restaurado"
        val composer = QueuedMessageComposer<String>()
        composer.bindDraft(stored) { stored = it }
        assertEquals("restaurado", composer.text.value)
        composer.updateText("pendiente")
        val failed = composer.beginSend()!!
        assertEquals("", composer.text.value)
        assertEquals("pendiente", stored) // Survives process death before local acceptance.
        composer.finished(failed)
        assertEquals("pendiente", stored)
        val queued = composer.beginSend()!!
        composer.queued(queued)
        assertEquals("", stored)
    }

    @Test fun switchingRoomsPreservesEachDraftAndFencesOldSendCallback() {
        var a = "A"; var b = "B"
        val composer = QueuedMessageComposer<String>()
        composer.bindDraft(a) { a = it }
        val old = composer.beginSend()!!
        composer.bindDraft(b) { b = it }
        composer.queued(old)
        assertEquals("A", a); assertEquals("B", b); assertEquals("B", composer.text.value)
        composer.reset()
        assertEquals("B", b)
    }

    @Test fun editingAnOldMessageNeverOverwritesTheUnsentDraft() {
        var stored = "mensaje sin enviar"
        val composer = QueuedMessageComposer<String>()
        composer.bindDraft(stored) { stored = it }
        composer.beginEditing("mensaje antiguo")
        composer.updateText("corregido")
        assertEquals("mensaje sin enviar", stored)
        composer.finishEditing()
        assertEquals(stored, composer.text.value)
    }
}
