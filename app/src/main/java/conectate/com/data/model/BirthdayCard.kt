package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BirthdayCard(
    val version: Int = 1,
    val kind: String = "birthday",
    val message: String = "¡Feliz cumpleaños! 🎂 Que este nuevo año te regale mucha alegría, salud y momentos inolvidables. ¡Disfruta tu día!",
    val theme: String = "confetti",
) {
    val isValid: Boolean get() = version == 1 && kind == "birthday" && theme in themes &&
        message.isNotBlank() && message.codePointCount(0, message.length) <= 600 &&
        message.none { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 }
    fun encode(): String = Json { encodeDefaults = true }.encodeToString(this)
    companion object {
        val themes = listOf("confetti", "sunrise", "garden")
        fun decode(value: String): BirthdayCard? = if (value.length > 5000) null else runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<BirthdayCard>(value)
        }.getOrNull()?.takeIf { it.isValid }
    }
}
