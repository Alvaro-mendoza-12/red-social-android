package conectate.com.data.supabase

private const val GENERIC_SUPABASE_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido en Supabase. No es un error de tu cuenta. Revisa la cuota y la facturación del proyecto en el panel de Supabase."

private const val STORAGE_QUOTA_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido porque el proyecto excedió la cuota de almacenamiento en Supabase. No es un error de tu cuenta. Hay que liberar espacio, revisar facturación o esperar al próximo reinicio del ciclo."

private const val DATABASE_QUOTA_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido porque el proyecto excedió la cuota de base de datos en Supabase. No es un error de tu cuenta. Hay que reducir uso, revisar facturación o esperar al próximo reinicio del ciclo."

private const val EGRESS_QUOTA_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido porque el proyecto excedió la cuota de transferencia en Supabase. No es un error de tu cuenta. Revisa el uso del proyecto o la facturación."

private const val BILLING_RESTRICTION_MESSAGE =
    "El servicio está temporalmente restringido por un problema de facturación en Supabase. No es un error de tu cuenta. Revisa pagos, método de cobro o soporte."

private val SUPABASE_RESTRICTION_PATTERNS = linkedMapOf(
    STORAGE_QUOTA_RESTRICTION_MESSAGE to listOf(
        "exceed_storage_size_quota",
        "cuota de almacenamiento"
    ),
    DATABASE_QUOTA_RESTRICTION_MESSAGE to listOf(
        "exceed_db_size_quota",
        "cuota de base de datos"
    ),
    EGRESS_QUOTA_RESTRICTION_MESSAGE to listOf(
        "exceed_egress_quota",
        "cuota de transferencia"
    ),
    BILLING_RESTRICTION_MESSAGE to listOf(
        "overdue_payment",
        "problema de facturación en supabase",
        "problema de facturacion en supabase"
    )
)

private val SUPABASE_GENERIC_RESTRICTION_PATTERNS = listOf(
    "service for this project is restricted",
    "fair use policy",
    "reach out to supabase support",
    "el servicio está temporalmente restringido",
    "el servicio esta temporalmente restringido",
    "proyecto excedió la cuota",
    "proyecto excedio la cuota"
)

fun mapSupabaseServiceRestrictionMessage(
    rawMessage: String?,
    fallback: String? = null
): String? {
    val normalized = rawMessage?.trim().orEmpty()
    if (normalized.isBlank()) return fallback

    val lower = normalized.lowercase()
    SUPABASE_RESTRICTION_PATTERNS.entries.firstOrNull { entry ->
        entry.value.any(lower::contains)
    }?.let { entry ->
        return entry.key
    }

    val looksRestricted =
        SUPABASE_GENERIC_RESTRICTION_PATTERNS.any(lower::contains) ||
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

fun isSupabaseServiceRestrictionMessage(message: String?): Boolean =
    mapSupabaseServiceRestrictionMessage(message, fallback = null) != null

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


