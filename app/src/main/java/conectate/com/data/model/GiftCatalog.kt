package conectate.com.data.model

import java.text.Normalizer

enum class GiftCategory {
    WELCOME,
    ENERGY,
    CELEBRATION,
    PREMIUM
}

enum class GiftTier {
    MICRO,
    FEATURED,
    CELEBRATION
}

/** Catálogo raster v1. Los precios son moneda virtual, nunca equivalencias fiat. */
val NOVVIN_CANONICAL_GIFT_CATALOG: List<VirtualGift> = listOf(
    canonicalGift(1, "starlight-seed", "Semilla Estelar", "Una chispa pequeña para iniciar la celebración", 5),
    canonicalGift(2, "aurora-drop", "Gota Aurora", "Una gota de luz suave para saludar al creador", 10),
    canonicalGift(3, "prism-spark", "Chispa Prisma", "Un destello multicolor que despierta el directo", 15),
    canonicalGift(4, "luminous-butterfly", "Mariposa Luminosa", "Alas brillantes para un saludo ligero", 25),
    canonicalGift(5, "comet-flower", "Flor Cometa", "Pétalos que dejan una estela en el cielo", 40),
    canonicalGift(6, "crystal-moon", "Luna de Cristal", "Una luna tallada en reflejos boreales", 60),
    canonicalGift(7, "solar-hummingbird", "Colibrí Solar", "Un vuelo veloz cargado de energía", 90),
    canonicalGift(8, "nebula-jellyfish", "Medusa Nebulosa", "Una criatura celeste que flota entre estrellas", 130),
    canonicalGift(9, "boreal-fox", "Zorro Boreal", "Un compañero ártico envuelto en auroras", 170),
    canonicalGift(10, "cosmic-whale", "Ballena Estelar", "Una travesía serena por el océano cósmico", 200),
    canonicalGift(11, "star-garden", "Jardín de Estrellas", "Un jardín que florece con luz estelar", 250),
    canonicalGift(12, "crystal-orchid", "Orquídea Prisma", "Cristales florales que reflejan todo el directo", 300),
    canonicalGift(13, "prism-ship", "Nave Prisma", "Una nave de colores lista para despegar", 350),
    canonicalGift(14, "forest-guardian", "Guardián del Bosque", "El protector luminoso de un bosque encantado", 450),
    canonicalGift(15, "aurora-phoenix", "Fénix Aurora", "Renace entre fuego frío y luces boreales", 600),
    canonicalGift(16, "star-train", "Tren Estelar", "Un viaje brillante que cruza toda la escena", 750),
    canonicalGift(17, "celestial-dragon", "Dragón Celeste", "Un guardián majestuoso para grandes celebraciones", 900),
    canonicalGift(18, "floating-island", "Isla Flotante", "Un mundo suspendido que aparece sobre el directo", 1_100),
    canonicalGift(19, "celestial-world", "Mundo Celeste", "Un planeta completo nacido entre constelaciones", 1_400),
    canonicalGift(20, "light-opera", "Ópera de Luz", "Una escena musical de luz, color y movimiento", 1_800),
    canonicalGift(21, "aurora-palace", "Palacio Boreal", "La celebración imperial del cielo nocturno", 2_200),
    canonicalGift(22, "time-kingdom", "Reino del Tiempo", "Un reino fantástico que controla cada instante", 2_800),
    canonicalGift(23, "galactic-ascent", "Ascenso Galáctico", "Una subida épica hacia el centro de la galaxia", 3_600),
    canonicalGift(24, "aurora-genesis", "Génesis Aurora", "El gran final Novvin: un universo nace en pantalla", 5_000)
)

val NOVVIN_GIFT_VISUAL_KEYS: List<String> = NOVVIN_CANONICAL_GIFT_CATALOG.map { it.visualKeyFromAssetUri() }

private val welcomeVisuals = setOf(
    "starlight-seed", "aurora-drop", "luminous-butterfly",
    "comet-flower", "nebula-jellyfish", "cosmic-whale"
)
private val energyVisuals = setOf(
    "prism-spark", "crystal-moon", "solar-hummingbird",
    "star-garden", "prism-ship", "star-train"
)
private val celebrationVisuals = setOf(
    "boreal-fox", "crystal-orchid", "forest-guardian",
    "aurora-phoenix", "celestial-dragon", "light-opera"
)

fun VirtualGift.category(): GiftCategory = when (visualKey()) {
    in welcomeVisuals -> GiftCategory.WELCOME
    in energyVisuals -> GiftCategory.ENERGY
    in celebrationVisuals -> GiftCategory.CELEBRATION
    else -> GiftCategory.PREMIUM
}

fun VirtualGift.tier(): GiftTier = when {
    priceDiamonds < 100 -> GiftTier.MICRO
    priceDiamonds < 900 -> GiftTier.FEATURED
    else -> GiftTier.CELEBRATION
}

/** Clave visual independiente del UUID que entregue cada backend. */
fun VirtualGift.visualKey(): String {
    val assetKey = visualKeyFromAssetUri()
    if (assetKey in NOVVIN_GIFT_VISUAL_KEYS) return assetKey

    val builtinKey = id.substringAfter("builtin-", missingDelimiterValue = "").normalizeGiftText()
    canonicalGiftVisual(builtinKey)?.let { return it }
    canonicalGiftVisual(name.normalizeGiftText())?.let { return it }

    val stableIndex = ((id.ifBlank { name }.hashCode() and Int.MAX_VALUE) % NOVVIN_GIFT_VISUAL_KEYS.size)
    return NOVVIN_GIFT_VISUAL_KEYS[stableIndex]
}

/** Conserva una sola variante por familia visual, incluso para filas legacy con tildes. */
fun List<VirtualGift>.withoutVisualDuplicates(): List<VirtualGift> =
    sortedWith(
        compareBy<VirtualGift> { if (it.imageUrl.orEmpty().startsWith("conectate://gift-assets/")) 0 else 1 }
            .thenBy { it.sortOrder }
            .thenBy { it.priceDiamonds }
            .thenBy { it.name }
    )
        .distinctBy { it.visualKey() }
        .sortedWith(compareBy<VirtualGift> { it.sortOrder }.thenBy { it.priceDiamonds }.thenBy { it.name })

/**
 * Conserva las filas reales que devolvió el backend y completa las familias
 * faltantes con el catálogo canónico, sin confiar el cobro al cliente.
 */
fun mergeGiftCatalog(
    remote: List<VirtualGift>,
    canonicalFallback: List<VirtualGift>
): List<VirtualGift> {
    val cleanRemote = remote.withoutVisualDuplicates()
    val remoteVisuals = cleanRemote.mapTo(mutableSetOf()) { it.visualKey() }
    return (cleanRemote + canonicalFallback.filter { it.visualKey() !in remoteVisuals })
        .withoutVisualDuplicates()
}

private fun canonicalGift(
    order: Int,
    visualKey: String,
    name: String,
    description: String,
    coins: Int
): VirtualGift = VirtualGift(
    id = "7a1000${order.toString().padStart(2, '0')}-0000-4000-8000-${order.toString().padStart(12, '0')}",
    name = name,
    description = description,
    imageUrl = "conectate://gift-assets/$visualKey",
    priceDiamonds = coins,
    priceCredits = 0.0,
    animationUrl = "conectate://gift-animations/$visualKey",
    sortOrder = order
)

private fun VirtualGift.visualKeyFromAssetUri(): String = imageUrl
    ?.substringAfter("gift-assets/", missingDelimiterValue = "")
    ?.substringBefore('?')
    ?.substringBefore('/')
    ?.normalizeGiftText()
    .orEmpty()

/** Mapea el catálogo antiguo a 16 artes raster distintos para upgrades sin placeholders. */
private fun canonicalGiftVisual(value: String): String? = when {
    value in NOVVIN_GIFT_VISUAL_KEYS -> value
    value.contains("heart") || value.contains("corazon") || value.contains("pulso") -> "starlight-seed"
    value.contains("rhythm") || value.contains("ritmo") || value.contains("music") -> "aurora-drop"
    value.contains("butterfly") || value.contains("mariposa") || value.contains("ala") -> "luminous-butterfly"
    value.contains("rose") || value.contains("rosa") || value.contains("flor") -> "comet-flower"
    value.contains("orbit") || value.contains("orbita") -> "cosmic-whale"
    value.contains("trophy") || value.contains("trofeo") || value.contains("copa") || value.contains("prisma") -> "prism-spark"
    value.contains("wave") || value.contains("marea") || value.contains("ola") -> "nebula-jellyfish"
    value.contains("firework") || value.contains("fuego") || value.contains("lluvia") -> "star-garden"
    value.contains("rainbow") || value.contains("arco") -> "solar-hummingbird"
    value.contains("superestrella") || value.contains("estrella") || value.contains("star") || value.contains("nova") -> "boreal-fox"
    value.contains("cristal") || value.contains("crystal") || value.contains("diamante") -> "crystal-moon"
    value.contains("comet") || value.contains("cometa") -> "prism-ship"
    value.contains("rocket") || value.contains("cohete") || value.contains("impulso") -> "star-train"
    value.contains("crown") || value.contains("corona") -> "aurora-palace"
    value.contains("portal") -> "floating-island"
    value.contains("fenix") || value.contains("phoenix") -> "aurora-phoenix"
    value.contains("ballena") || value.contains("whale") -> "cosmic-whale"
    value.contains("orquidea") || value.contains("orchid") -> "crystal-orchid"
    value.contains("dragon") -> "celestial-dragon"
    value.contains("palacio") || value.contains("palace") -> "aurora-palace"
    else -> null
}

private fun String.normalizeGiftText(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")
    .lowercase()
    .replace("[^a-z0-9]+".toRegex(), "-")
    .trim('-')
