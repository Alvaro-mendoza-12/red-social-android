package conectate.com.presentation.auth

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A slow public section cannot withhold another section's ready images. */
internal suspend fun loadGuestSocialSections(
    posts: suspend () -> Unit,
    moments: suspend () -> Unit,
) = coroutineScope {
    launch { posts() }
    launch { moments() }
    Unit
}
