package conectate.com.data.supabase

private const val GENERIC_SUPABASE_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido en Supabase. No es un error de tu cuenta. Revisa la cuota y la facturación del proyecto en el panel de Supabase."

private val SUPABASE_RESTRICTION_MESSAGES = linkedMapOf(
    "exceed_storage_size_quota" to
        "El servicio está temporalmente restringido porque el proyecto excedió la cuota de almacenamiento en Supabase. No es un error de tu cuenta. Hay que liberar espacio, revisar facturación o esperar al próximo reinicio del ciclo.",
    "exceed_db_size_quota" to
        "El servicio está temporalmente restringido porque el proyecto excedió la cuota de base de datos en Supabase. No es un error de tu cuenta. Hay que reducir uso, revisar facturación o esperar al próximo reinicio del ciclo.",
    "exceed_egress_quota" to
        "El servicio está temporalmente restringido porque el proyecto excedió la cuota de transferencia en Supabase. No es un error de tu cuenta. Revisa el uso del proyecto o la facturación.",
    "overdue_payment" to
        "El servicio está temporalmente restringido por un problema de facturación en Supabase. No es un error de tu cuenta. Revisa pagos, método de cobro o soporte."
)

fun mapSupabaseServiceRestrictionMessage(
    rawMessage: String?,
    fallback: String? = null
): String? {
    val normalized = rawMessage?.trim().orEmpty()
    if (normalized.isBlank()) return fallback

    val lower = normalized.lowercase()
    SUPABASE_RESTRICTION_MESSAGES.entries.firstOrNull { lower.contains(it.key) }?.let {
        return it.value
    }

    val looksRestricted =
        lower.contains("service for this project is restricted") ||
            lower.contains("fair use policy") ||
            lower.contains("reach out to supabase support") ||
            (
                lower.contains("402") &&
                    (
                        lower.contains("restricted") ||
                            lower.contains("quota") ||
                            lower.contains("overdue")
                        )
                )

    return when {
        looksRestricted -> GENERIC_SUPABASE_RESTRICTION_MESSAGE
        else -> fallback
    }
}

fun buildSupabaseHttpException(
    statusCode: Int,
    responseBody: String?,
    fallbackMessage: String
): Exception {
    val statusFallback = if (statusCode == 402) {
        GENERIC_SUPABASE_RESTRICTION_MESSAGE
    } else {
        fallbackMessage
    }
    val resolvedMessage = mapSupabaseServiceRestrictionMessage(responseBody, statusFallback)
        ?: statusFallback
    return Exception(resolvedMessage)
}


