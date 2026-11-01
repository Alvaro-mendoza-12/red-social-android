package conectate.com.core.messaging

/** A durable outbox entry alone must never complete the content-share selector. */
internal fun messageSendConfirmation(
    synced: Boolean,
    status: String,
    pendingAction: String? = null,
    deletedForAll: Boolean = false,
): Result<Unit> = if (synced && pendingAction == null && !deletedForAll &&
    status.trim().lowercase() in setOf("sent", "delivered", "read")) {
    Result.success(Unit)
} else {
    Result.failure(IllegalStateException("El mensaje sigue pendiente de confirmación. Vuelve a intentarlo."))
}
