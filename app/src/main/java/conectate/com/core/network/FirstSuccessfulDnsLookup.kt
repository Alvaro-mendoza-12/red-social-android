package conectate.com.core.network

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A broken resolver must not delay another resolver's successful answer. */
internal fun firstSuccessfulDnsLookup(
    executor: ExecutorService,
    tasks: List<() -> List<InetAddress>>,
    timeoutMs: Long
): List<InetAddress> {
    val completion = ExecutorCompletionService<List<InetAddress>>(executor)
    val futures = tasks.map { task -> completion.submit(Callable {
        runCatching { task() }.getOrDefault(emptyList())
    }) }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    try {
        repeat(futures.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return emptyList()
            val result = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return emptyList()
            val addresses = runCatching { result.get() }.getOrDefault(emptyList())
            if (addresses.isNotEmpty()) return addresses
        }
        return emptyList()
    } finally {
        futures.filterNot { it.isDone }.forEach { it.cancel(true) }
    }
}
