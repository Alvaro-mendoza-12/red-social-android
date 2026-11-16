package conectate.com.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import conectate.com.R

/** The password already changed; these actions only acknowledge or retry local cleanup. */
@Composable
fun EmailRecoveryCleanupDialog(
    requiresTwoFactor: Boolean,
    working: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    requiresSignIn: Boolean = requiresTwoFactor,
) {
    AlertDialog(
        modifier = Modifier.testTag("email_recovery_cleanup_notice"),
        onDismissRequest = {},
        title = { Text(stringResource(R.string.auth_email_cleanup_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.auth_email_cleanup_failed))
                if (requiresTwoFactor) Text(stringResource(R.string.auth_email_cleanup_two_factor),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (requiresSignIn) Text(stringResource(R.string.auth_email_confirmation_sign_in),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (working) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.auth_email_cleanup_working), Modifier.testTag("email_recovery_cleanup_progress"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = !working,
                modifier = Modifier.heightIn(min = 48.dp).testTag("email_recovery_cleanup_retry")) {
                Text(stringResource(R.string.auth_email_cleanup_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onContinue, enabled = !working,
                modifier = Modifier.heightIn(min = 48.dp).testTag("email_recovery_cleanup_continue")) {
                Text(stringResource(R.string.auth_email_cleanup_continue))
            }
        },
    )
}
