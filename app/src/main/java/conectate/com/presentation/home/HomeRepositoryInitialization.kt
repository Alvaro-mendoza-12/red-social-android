package conectate.com.presentation.home

import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolve optional home repositories without loading their graph during composition. */
internal suspend fun <T> resolveHomeRepository(provider: Lazy<T>): T =
    withContext(Dispatchers.IO) { provider.get() }
