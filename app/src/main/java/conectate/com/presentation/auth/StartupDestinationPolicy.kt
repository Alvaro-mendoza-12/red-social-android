package conectate.com.presentation.auth

internal enum class StartupDestination { HOME, LOGIN, GUEST, ADD_ACCOUNT }

internal fun startupDestination(loggedIn: Boolean, recoveryPending: Boolean, savedGuest: Boolean) = when {
    recoveryPending -> if (loggedIn) StartupDestination.ADD_ACCOUNT else StartupDestination.LOGIN
    loggedIn -> StartupDestination.HOME
    savedGuest -> StartupDestination.GUEST
    else -> StartupDestination.LOGIN
}
