package conectate.com.data.model

import java.text.Normalizer

/** Visible names are independent from immutable @handles; existing names stay intact. */
object DisplayNamePolicy {
    const val MAX_CHARACTERS = 20
    fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    fun length(value: String): Int = normalized(value).let { it.codePointCount(0, it.length) }
    fun valid(value: String): Boolean = length(value) in 2..MAX_CHARACTERS && !value.any(Char::isISOControl)
    fun input(value: String): String {
        val count = value.codePointCount(0, value.length)
        return if (count <= MAX_CHARACTERS) value else value.substring(0, value.offsetByCodePoints(0, MAX_CHARACTERS))
    }
}
