package conectate.com.presentation.components

import androidx.compose.runtime.compositionLocalOf

/** Null is an anonymous bounded cache; blank explicitly disables persistence during account changes. */
val LocalProfileIdentityAccount = compositionLocalOf<String?> { null }
