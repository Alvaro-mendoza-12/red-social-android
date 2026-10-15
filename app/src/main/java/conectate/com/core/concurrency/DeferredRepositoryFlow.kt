package conectate.com.core.concurrency

import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Construct shared dependencies on IO, including when another caller owns their Dagger lock. */
internal fun <T, R> deferredRepositoryFlow(provider: Lazy<T>, observe: (T) -> Flow<R>): Flow<R> = flow {
    emitAll(withContext(Dispatchers.IO) { observe(provider.get()) })
}
