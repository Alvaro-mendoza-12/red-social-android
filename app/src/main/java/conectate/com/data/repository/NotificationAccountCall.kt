package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionLease

/** Credentials and results belong to the same account generation, even across token renewal. */
internal suspend fun <T> notificationAccountCall(
    lease: AuthSessionLease,
    tokenForAccount: suspend (String) -> String?,
    action: suspend (owner: String, accessToken: String) -> Result<T>,
): Result<T> = accountScopedCall(lease, tokenForAccount, action)
