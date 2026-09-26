package conectate.com.core.media

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Bounded workers can drain the entire inbox instead of permanently selecting its first page. */
internal suspend fun <T> warmProfileIdentityBatch(items: List<T>, load: suspend (T) -> Unit) = coroutineScope {
    repeat(minOf(3, items.size)) { worker ->
        launch { for (index in worker until items.size step 3) load(items[index]) }
    }
}
