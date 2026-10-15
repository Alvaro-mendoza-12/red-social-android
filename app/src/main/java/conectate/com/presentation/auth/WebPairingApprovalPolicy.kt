package conectate.com.presentation.auth

internal fun webPairingApprovalSessionValid(accessToken: String, refreshToken: String, requiresRefreshToken: Boolean): Boolean =
    accessToken.isNotBlank() && (!requiresRefreshToken || refreshToken.isNotBlank())
