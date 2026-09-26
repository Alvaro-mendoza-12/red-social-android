package conectate.com.data.repository.message

import conectate.com.data.model.ScheduledMessageJob
import java.util.Locale

/**
 * Ubuntu stores JWT.sub as owner; a migrated Android account may use its public
 * Supabase ID instead. Only that captured token's subject is an accepted alias.
 * This is response correlation, not a replacement for server authentication.
 */
internal fun bindScheduledJobToAccount(
    job: ScheduledMessageJob,
    key: ScheduledMessagesKey,
    tokenSubject: String?,
): ScheduledMessageJob {
    fun clean(value: String?) = value?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
    val owner = clean(job.owner_id)
    check(job.id.isNotBlank() &&
        ScheduledMessagesKey.create(key.accountId, job.scope_type, job.target_id) == key &&
        (owner == null || owner == key.accountId || owner == clean(tokenSubject))
    ) { "Respuesta de mensajes programados inválida" }
    return job.copy(owner_id = key.accountId)
}
