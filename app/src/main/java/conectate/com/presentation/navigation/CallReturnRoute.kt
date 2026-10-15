package conectate.com.presentation.navigation

/** Only app-owned, trusted intents reach this resolver; never put raw extras into routes. */
internal fun resolveCallReturnRoute(callId: String?): String? = callId?.trim()
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) }
    ?.let(Screen.Call::createRoute)
