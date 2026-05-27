package conectate.com.data.local.prefs

enum class AppFontOption(
    val storageValue: String,
    val label: String,
    val previewName: String = label
) {
    DEFAULT("default", "Predeterminada"),
    SANS_SERIF("sans_serif", "Sans Serif"),
    ROUNDED("rounded", "Redondeada"),
    CONDENSED("condensed", "Condensada"),
    MEDIUM("medium", "Mediana"),
    LIGHT("light", "Ligera"),
    SERIF("serif", "Serif"),
    SMALL_CAPS("small_caps", "Pequeñas mayúsculas"),
    SERIF_MONO("serif_mono", "Serif Mono"),
    MONOSPACE("monospace", "Monospace"),
    CURSIVE("cursive", "Cursiva"),
    CASUAL("casual", "Casual");

    companion object {
        fun fromStorage(raw: String?): AppFontOption {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.storageValue == normalized } ?: DEFAULT
        }
    }
}


