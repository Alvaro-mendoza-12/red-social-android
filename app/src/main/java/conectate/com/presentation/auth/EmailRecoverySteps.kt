package conectate.com.presentation.auth

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.R
import kotlinx.coroutines.delay

@Composable
fun EmailRecoverySteps(
    draft: EmailRecoveryDraft,
    loading: Boolean,
    newPassword: String,
    confirmation: String,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSavePassword: () -> Unit,
    onResend: () -> Unit,
    onChangeEmail: () -> Unit,
) {
    val context = LocalContext.current
    val copyCodeHint = stringResource(R.string.auth_email_copy_code_hint)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(draft.resendAt) {
        do { now = System.currentTimeMillis(); if (now >= draft.resendAt) break; delay(1_000) } while (true)
    }
    val seconds = ((draft.resendAt - now + 999) / 1_000).coerceAtLeast(0).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (draft.verified) R.string.auth_email_password_step else R.string.auth_email_code_step),
            style = MaterialTheme.typography.titleLarge)
        Text(draft.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!draft.verified) {
            Text(stringResource(if (draft.deliveryConfirmed) R.string.auth_email_code_sent_hint
                else R.string.auth_email_delivery_unknown_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = draft.code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.auth_email_six_digit_code)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 4.sp),
                modifier = Modifier.fillMaxWidth().testTag("email_recovery_code"),
                singleLine = true, enabled = !loading,
            )
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                val codes = Regex("(?<![0-9])[0-9]{6}(?![0-9])").findAll(text).map { it.value }.toList()
                if (codes.size == 1) onCodeChange(codes.single())
                else android.widget.Toast.makeText(context, copyCodeHint, android.widget.Toast.LENGTH_SHORT).show()
            }, enabled = !loading) { Text(stringResource(R.string.auth_email_paste_code)) }
            Button(onClick = onVerify, enabled = !loading && draft.code.length == 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("email_recovery_verify")) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.auth_email_verify_code))
            }
        } else {
            Text(stringResource(R.string.auth_email_code_verified), color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = newPassword, onValueChange = onNewPasswordChange,
                label = { Text(stringResource(R.string.auth_new_password)) },
                supportingText = { Text(stringResource(R.string.auth_email_password_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading,
                modifier = Modifier.fillMaxWidth().testTag("email_recovery_password"))
            OutlinedTextField(value = confirmation, onValueChange = onConfirmationChange,
                label = { Text(stringResource(R.string.auth_repeat_new_password)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !loading,
                isError = confirmation.isNotBlank() && confirmation != newPassword,
                supportingText = { if (confirmation.isNotBlank() && confirmation != newPassword) Text(stringResource(R.string.auth_email_password_mismatch)) },
                modifier = Modifier.fillMaxWidth().testTag("email_recovery_confirmation"))
            Button(onClick = onSavePassword,
                enabled = !loading && newPassword.length >= 8 && newPassword == confirmation,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("email_recovery_save")) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.auth_recovery_save_and_sign_in))
            }
        }
        if (!draft.verified) OutlinedButton(onClick = onResend, enabled = !loading && seconds == 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("email_recovery_resend")) {
            Text(if (seconds > 0) stringResource(R.string.auth_recovery_resend_wait, seconds)
                else stringResource(R.string.auth_recovery_resend_email_code))
        }
        TextButton(onClick = onChangeEmail, enabled = !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.auth_email_change_email)) }
    }
}
