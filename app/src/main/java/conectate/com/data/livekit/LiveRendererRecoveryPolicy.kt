package conectate.com.data.livekit

import java.util.concurrent.atomic.AtomicLong

/** Bounded repair window for a newly attached or restored native video sink. */
internal class LiveRendererRecoveryPolicy(private val attachedAtMs: Long) {
    private val lastFrameAtMs = AtomicLong(attachedAtMs)
    private var lastRepairAtMs = attachedAtMs
    private var attempts = 0

    fun recordFrame(nowMs: Long) {
        lastFrameAtMs.accumulateAndGet(nowMs, ::maxOf)
    }

    // Called serially by the UI owner. A frame callback only updates the clock.
    fun claimRepair(nowMs: Long, active: Boolean): Boolean {
        if (!active || nowMs < attachedAtMs || nowMs - attachedAtMs > 10_000 || attempts >= 2) return false
        if (nowMs - lastFrameAtMs.get() < 1_500 || nowMs - lastRepairAtMs < 1_500) return false
        attempts++
        lastRepairAtMs = nowMs
        return true
    }
}
