package conectate.com.presentation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedMessageComposerTest {
    @Test fun `local acceptance clears composer and allows another send before server responds`() = runTest {
        val composer = QueuedMessageComposer<String>()
        val localSave = CompletableDeferred<Unit>()
        val server = CompletableDeferred<Unit>()
        composer.updateText("Primero")
        composer.updateReply("original")
        val first = requireNotNull(composer.beginSend())
        val delivery = launch {
            try {
                localSave.await()
                composer.queued(first)
                server.await()
            } finally {
                composer.finished(first)
            }
        }
        runCurrent()
        assertEquals("Primero", composer.text.value)
        assertNull(composer.beginSend()) // Repeated tap while saving cannot duplicate it.

        localSave.complete(Unit)
        runCurrent()
        assertEquals("", composer.text.value)
        assertNull(composer.reply.value)
        assertFalse(delivery.isCompleted)

        composer.updateText("Segundo")
        composer.updateReply("otra respuesta")
        val second = requireNotNull(composer.beginSend())
        server.complete(Unit)
        runCurrent()
        assertEquals("Segundo", composer.text.value)
        assertEquals("otra respuesta", composer.reply.value)
        assertNull(composer.beginSend()) // First completion must not unlock the second save.
        composer.queued(second)
        assertEquals("", composer.text.value)
        assertEquals("original", first.replyTo)
        assertEquals("otra respuesta", second.replyTo)
    }

    @Test fun `rejection before local save retains draft and reply for retry`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("No perder")
        composer.updateReply("mensaje")
        val rejected = requireNotNull(composer.beginSend())
        composer.finished(rejected)
        assertEquals("No perder", composer.text.value)
        assertEquals("mensaje", composer.reply.value)
        assertNotNull(composer.beginSend())
    }

    @Test fun `late delivery failure never restores sent text over the new draft`() = runTest {
        val composer = QueuedMessageComposer<String>()
        val server = CompletableDeferred<Unit>()
        composer.updateText("Enviado")
        val first = requireNotNull(composer.beginSend())
        val delivery = launch {
            try {
                composer.queued(first)
                server.await()
                throw IllegalStateException("Network failure")
            } catch (_: IllegalStateException) {
                // ViewModels display the delivery error; the message remains in the outbox.
            } finally {
                composer.finished(first)
            }
        }
        runCurrent()
        composer.updateText("Borrador nuevo")
        composer.updateReply("nuevo destino")
        server.complete(Unit)
        delivery.join()
        assertEquals("Borrador nuevo", composer.text.value)
        assertEquals("nuevo destino", composer.reply.value)
    }

    @Test fun `typing an identical draft before local acceptance preserves the new revision`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Hola")
        composer.updateReply("same-id")
        val first = requireNotNull(composer.beginSend())
        composer.updateText("")
        composer.updateText("Hola")
        composer.updateReply(null)
        composer.updateReply("same-id")
        composer.queued(first)
        assertEquals("Hola", composer.text.value)
        assertEquals("same-id", composer.reply.value)
        assertNotNull(composer.beginSend())
    }

    @Test fun `editing while validation fails preserves the replacement draft`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Antes")
        val first = requireNotNull(composer.beginSend())
        composer.updateText("Corrección")
        composer.finished(first)
        assertEquals("Corrección", composer.text.value)
    }

    @Test fun `forced content does not consume matching composer text`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Hola")
        val first = requireNotNull(composer.beginSend(forcedContent = "Hola"))
        composer.queued(first)
        assertEquals("Hola", composer.text.value)
    }

    @Test fun `account or conversation reset detaches old callbacks from a new submission`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Cuenta A")
        val old = requireNotNull(composer.beginSend())
        composer.reset()
        composer.updateText("Cuenta B")
        val current = requireNotNull(composer.beginSend())
        composer.queued(old)
        composer.finished(old)
        assertEquals("Cuenta B", composer.text.value)
        assertNull(composer.beginSend())
        composer.queued(current)
        assertEquals("", composer.text.value)
    }

    @Test fun `channel switch keeps the draft and ignores acknowledgement from previous channel`() {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Texto")
        val previous = requireNotNull(composer.beginSend())
        composer.invalidatePendingSend()
        composer.queued(previous)
        assertEquals("Texto", composer.text.value)
        assertNotNull(composer.beginSend())
    }

    @Test fun `cancellation before local save unlocks draft without deleting it`() = runTest {
        val composer = QueuedMessageComposer<String>()
        composer.updateText("Pendiente")
        val submission = requireNotNull(composer.beginSend())
        val localSave = CompletableDeferred<Unit>()
        val job = launch {
            try {
                localSave.await()
                composer.queued(submission)
            } finally {
                composer.finished(submission)
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals("Pendiente", composer.text.value)
        assertNotNull(composer.beginSend())
    }
}
