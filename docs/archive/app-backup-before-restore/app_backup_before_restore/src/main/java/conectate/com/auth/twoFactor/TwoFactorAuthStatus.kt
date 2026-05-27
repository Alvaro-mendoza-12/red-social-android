package conectate.com.auth.twoFactor

data class TwoFactorAuthStatus(
    val isEnabled: Boolean,
    val failedAttempts: Int,
    val isLockedOut: Boolean,
    val remainingLockoutTime: Long,
    val unusedBackupCodesCount: Int
)
