package conectate.com.core.media

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class InteractiveMediaKind { LIVE, VOICE_ROOM }

/** Navigation keeps ownership; only a completed exit releases audio/capture. */
@Singleton
class InteractiveMediaGate @Inject constructor() {
    private val owner = AtomicReference<InteractiveMediaKind?>(null)
    fun canEnter(kind: InteractiveMediaKind) = owner.get().let { it == null || it == kind }
    fun acquire(kind: InteractiveMediaKind): Boolean = owner.compareAndSet(null, kind) || owner.get() == kind
    fun release(kind: InteractiveMediaKind) { owner.compareAndSet(kind, null) }
    fun conflictMessage() = if (owner.get() == InteractiveMediaKind.LIVE)
        "Tienes un directo activo. Vuelve al directo y sal o finalízalo antes de entrar a una sala de voz."
    else "Estás en una sala de voz. Sal de la sala antes de entrar a un directo."
}
