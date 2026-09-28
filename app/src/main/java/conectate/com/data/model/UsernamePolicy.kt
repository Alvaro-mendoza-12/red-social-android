package conectate.com.data.model

import java.text.Normalizer
import java.util.Locale

fun suggestedPublicUsername(name: String): String {
    val seed = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")
        .take(24)
    return if (seed.length >= 3) seed else "usuario$seed".take(24)
}

fun publicUsernameFormatError(value: String): String? = when {
    value.isBlank() -> "Escribe un @usuario para continuar."
    value.length < 3 -> "El @usuario necesita al menos 3 caracteres."
    value.length > 24 -> "El @usuario admite hasta 24 caracteres."
    !Regex("[a-z0-9_.-]+", RegexOption.IGNORE_CASE).matches(value) ->
        "Usa letras sin tildes, números, puntos, guiones o guiones bajos; sin espacios."
    else -> null
}
