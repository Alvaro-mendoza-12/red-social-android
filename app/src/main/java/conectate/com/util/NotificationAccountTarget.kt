package conectate.com.util

import android.content.Intent
import conectate.com.data.model.StoredAccountSession

data class NotificationAccountTarget(
    val userId: String? = null,
    val email: String? = null,
    val displayName: String? = null
) {
    val isDefined: Boolean
        get() = !userId.isNullOrBlank() || !email.isNullOrBlank()

    fun matches(userId: String?, email: String?): Boolean {
        val cleanUserId = userId.normalizeUserId()
        val cleanEmail = email.normalizeEmail()
        return (!this.userId.isNullOrBlank() && !cleanUserId.isNullOrBlank() && this.userId == cleanUserId) ||
            (!this.email.isNullOrBlank() && !cleanEmail.isNullOrBlank() && this.email == cleanEmail)
    }

    fun matches(account: StoredAccountSession): Boolean = matches(account.user_id, account.email)

    fun presentableLabel(fallback: String? = null): String? {
        return displayName.normalizeDisplayName()
            ?: fallback.normalizeDisplayName()
    }
}

object NotificationAccountTargetContract {
    const val EXTRA_TARGET_ACCOUNT_USER_ID = "notification_target_account_user_id"
    const val EXTRA_TARGET_ACCOUNT_EMAIL = "notification_target_account_email"
    const val EXTRA_TARGET_ACCOUNT_LABEL = "notification_target_account_label"
}

fun notificationAccountTargetFromRemoteData(data: Map<String, String>): NotificationAccountTarget? {
    var userId = firstNonBlank(
        data["recipient_id"],
        data["recipientId"],
        data["recipient_user_id"],
        data["recipientUserId"],
        data["account_user_id"],
        data["accountUserId"],
        data["notification_account_user_id"]
    ).normalizeUserId()

    var email = firstNonBlank(
        data["recipient_email"],
        data["recipientEmail"],
        data["account_email"],
        data["accountEmail"],
        data["notification_account_email"]
    ).normalizeEmail()

    var displayName = firstNonBlank(
        data["recipient_display_name"],
        data["recipientDisplayName"],
        data["account_display_name"],
        data["accountDisplayName"],
        data["recipient_name"],
        data["recipientName"],
        data["notification_account_label"]
    ).normalizeDisplayName()

    return NotificationAccountTarget(
        userId = userId,
        email = email,
        displayName = displayName
    ).takeIf { it.isDefined }
}

fun notificationAccountTargetFromIntent(intent: Intent): NotificationAccountTarget? {
    var userId = firstNonBlank(
        intent.getStringExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_USER_ID),
        intent.getStringExtra("recipient_id"),
        intent.getStringExtra("recipient_user_id"),
        intent.getStringExtra("account_user_id")
    ).normalizeUserId()

    var email = firstNonBlank(
        intent.getStringExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_EMAIL),
        intent.getStringExtra("recipient_email"),
        intent.getStringExtra("account_email")
    ).normalizeEmail()

    var displayName = firstNonBlank(
        intent.getStringExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_LABEL),
        intent.getStringExtra("recipient_display_name"),
        intent.getStringExtra("account_display_name")
    ).normalizeDisplayName()

    return NotificationAccountTarget(
        userId = userId,
        email = email,
        displayName = displayName
    ).takeIf { it.isDefined }
}

fun Intent.putNotificationAccountTarget(
    target: NotificationAccountTarget?,
    fallbackLabel: String? = null
): Intent {
    if (target == null || !target.isDefined) return this
    target.userId?.let {
        putExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_USER_ID, it)
    }
    target.email?.let {
        putExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_EMAIL, it)
    }
    target.presentableLabel(fallbackLabel)?.let {
        putExtra(NotificationAccountTargetContract.EXTRA_TARGET_ACCOUNT_LABEL, it)
    }
    return this
}

fun resolveStoredAccountForTarget(
    target: NotificationAccountTarget?,
    accounts: List<StoredAccountSession>
): StoredAccountSession? {
    if (target == null || !target.isDefined) return null
    return accounts.firstOrNull { target.matches(it) }
}

/** A removed account must never create an alert or an unread hint on this installation. */
fun canReceiveAccountNotification(
    target: NotificationAccountTarget?,
    activeUserId: String?,
    activeEmail: String?,
    activeSessionAvailable: Boolean,
    accounts: List<StoredAccountSession>
): Boolean {
    if (target == null || !target.isDefined) {
        return activeSessionAvailable && !activeUserId.isNullOrBlank() &&
            accounts.all { it.user_id?.trim()?.equals(activeUserId.trim(), true) == true }
    }
    if (activeSessionAvailable && target.matches(activeUserId, activeEmail)) return true
    return accounts.any { target.matches(it) && it.access_token.isNotBlank() && it.refresh_token.isNotBlank() }
}

fun storedAccountDisplayLabel(account: StoredAccountSession): String {
    return account.display_name.normalizeDisplayName()
        ?: account.email.normalizeEmail()?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: "Cuenta"
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun String?.normalizeUserId(): String? {
    return this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

private fun String?.normalizeEmail(): String? {
    return this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

private fun String?.normalizeDisplayName(): String? {
    return this?.trim()?.takeIf { it.isNotBlank() }
}




