package conectate.com.core.media

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class InteractiveMediaKind { LIVE, VOICE_ROOM, CALL }

/** Navigation keeps ownership; only a completed exit releases audio/capture. */
@Singleton
class InteractiveMediaGate @Inject constructor() {
    private val owner = AtomicReference<InteractiveMediaKind?>(null)
    fun canEnter(kind: InteractiveMediaKind) = owner.get().let { it == null || it == kind }
    fun acquire(kind: InteractiveMediaKind): Boolean = owner.compareAndSet(null, kind) || owner.get() == kind
    fun acquireExclusive(kind: InteractiveMediaKind): Boolean = owner.compareAndSet(null, kind)

    @Synchronized
    fun withOwner(kind: InteractiveMediaKind, action: () -> Unit) {
        if (owner.get() == kind) action()
    }

    @Synchronized
    fun release(kind: InteractiveMediaKind, beforeRelease: () -> Unit = {}) {
        if (owner.get() != kind) return
        try {
            beforeRelease()
        } finally {
            owner.set(null)
        }
    }

    fun conflictMessage(): String = when (owner.get()) {
        InteractiveMediaKind.LIVE -> "Tienes un directo activo. Sal del directo o finalízalo antes de continuar."
        InteractiveMediaKind.VOICE_ROOM -> "Estás en una sala de voz. Sal de la sala antes de continuar."
        InteractiveMediaKind.CALL -> "Tienes una llamada activa. Finaliza la llamada antes de continuar."
        null -> "La sesión anterior acaba de cambiar. Inténtalo de nuevo."
    }
}
