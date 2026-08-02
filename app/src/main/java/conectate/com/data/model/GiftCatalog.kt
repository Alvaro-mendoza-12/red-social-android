package conectate.com.data.model

import java.text.Normalizer

val NOVVIN_GIFT_VISUAL_KEYS = listOf(
    "pulse",
    "rhythm",
    "butterfly",
    "aurora",
    "orbit",
    "prism",
    "wave",
    "fireworks",
    "rainbow",
    "nova",
    "crystal",
    "comet",
    "rocket",
    "crown",
    "portal",
    "phoenix"
)

/** Clave visual independiente del UUID que entregue cada backend. */
fun VirtualGift.visualKey(): String {
    val assetKey = imageUrl
        ?.substringAfter("gift-assets/", missingDelimiterValue = "")
        ?.substringBefore('?')
        ?.substringBefore('/')
        ?.normalizeGiftText()
        .orEmpty()
    if (assetKey in NOVVIN_GIFT_VISUAL_KEYS) return assetKey

    val builtinKey = id.substringAfter("builtin-", missingDelimiterValue = "").normalizeGiftText()
    canonicalGiftVisual(builtinKey)?.let { return it }
    canonicalGiftVisual(name.normalizeGiftText())?.let { return it }

    val stableIndex = ((id.ifBlank { name }.hashCode() and Int.MAX_VALUE) % NOVVIN_GIFT_VISUAL_KEYS.size)
    return NOVVIN_GIFT_VISUAL_KEYS[stableIndex]
}

/** El catálogo importado contenía duplicados con y sin tildes. Conserva una sola variante real. */
fun List<VirtualGift>.withoutVisualDuplicates(): List<VirtualGift> =
    sortedWith(
        compareBy<VirtualGift> { if (it.imageUrl.orEmpty().startsWith("conectate://gift-assets/")) 0 else 1 }
            .thenBy { it.sortOrder }
            .thenBy { it.priceDiamonds }
            .thenBy { it.name }
    )
        .distinctBy { it.visualKey() }
        .sortedWith(compareBy<VirtualGift> { it.sortOrder }.thenBy { it.priceDiamonds }.thenBy { it.name })

private fun canonicalGiftVisual(value: String): String? = when {
    value in NOVVIN_GIFT_VISUAL_KEYS -> value
    value.contains("heart") || value.contains("corazon") || value.contains("pulso") -> "pulse"
    value.contains("rose") || value.contains("rosa") || value.contains("flor") || value.contains("aurora") -> "aurora"
    value.contains("trophy") || value.contains("trofeo") || value.contains("copa") || value.contains("prisma") -> "prism"
    value.contains("firework") || value.contains("fuego") || value.contains("lluvia") -> "fireworks"
    value.contains("superestrella") || value.contains("estrella") || value.contains("star") || value.contains("nova") -> "nova"
    value.contains("orbit") -> "orbit"
    value.contains("comet") -> "comet"
    value.contains("crown") || value.contains("corona") -> "crown"
    value.contains("cristal") || value.contains("crystal") || value.contains("diamante") -> "crystal"
    value.contains("cohete") || value.contains("rocket") || value.contains("impulso") -> "rocket"
    value.contains("ritmo") || value.contains("music") -> "rhythm"
    value.contains("arco") || value.contains("rainbow") -> "rainbow"
    value.contains("marea") || value.contains("wave") || value.contains("ola") -> "wave"
    value.contains("mariposa") || value.contains("butterfly") || value.contains("ala") -> "butterfly"
    value.contains("portal") -> "portal"
    value.contains("fenix") || value.contains("phoenix") -> "phoenix"
    else -> null
}

private fun String.normalizeGiftText(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")
    .lowercase()
    .replace("[^a-z0-9]+".toRegex(), "-")
    .trim('-')
