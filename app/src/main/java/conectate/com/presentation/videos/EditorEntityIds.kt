package conectate.com.presentation.videos

import java.util.concurrent.atomic.AtomicLong

private val editorIdSequence = AtomicLong(0)

/** Rapid edits must not share an ID; restored projects can already contain clock-based IDs. */
internal fun nextEditorEntityId(reserved: Collection<Long> = emptyList()): Long {
    while (true) {
        val candidate = editorIdSequence.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis()) }
        if (candidate !in reserved) return candidate
    }
}
