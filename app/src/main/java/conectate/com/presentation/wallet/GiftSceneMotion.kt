package conectate.com.presentation.wallet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Una familia de movimiento por cada arte canónico. Estas familias son datos
 * estables: no dependen del UUID que entregue el backend ni de la densidad del
 * dispositivo, por lo que la misma celebración se ve igual en móvil y tablet.
 */
internal enum class GiftMotionProfile {
    SEED,
    DROP,
    SPARK,
    BUTTERFLY,
    FLOWER,
    MOON,
    HUMMINGBIRD,
    JELLYFISH,
    FOX,
    WHALE,
    GARDEN,
    ORCHID,
    SHIP,
    GUARDIAN,
    PHOENIX,
    TRAIN,
    DRAGON,
    ISLAND,
    WORLD,
    OPERA,
    PALACE,
    KINGDOM,
    ASCENT,
    GENESIS
}

internal enum class GiftSceneEffect {
    GERMINATION,
    RAIN_RIPPLE,
    PRISM_BURST,
    WING_TRAIL,
    PETAL_COMET,
    LUNAR_ORBITS,
    GLITTER_DUST,
    NEBULA_TENTACLES,
    AURORA_PAWS,
    COSMIC_WAKE,
    BLOOM_FIELD,
    CRYSTAL_PETALS,
    ENGINE_TRAIL,
    FOREST_RUNES,
    AURORA_FLAMES,
    STELLAR_RAIL,
    CELESTIAL_ARC,
    CLOUD_ISLAND,
    PLANET_RINGS,
    LIGHT_CURTAINS,
    PALACE_BEAMS,
    CLOCKWORK_RINGS,
    GALAXY_LIFT,
    GENESIS_WAVE
}

internal data class GiftSceneSpec(
    val effect: GiftSceneEffect,
    val sound: GiftSoundProfile,
    val artScale: Float,
    val particleCount: Int,
    val layerCount: Int,
    val fullScreen: Boolean
)

/** Traslaciones normalizadas: +/-1 desplaza el centro medio ancho/alto. */
internal data class GiftMotionTransform(
    val scale: Float,
    val rotation: Float,
    val translationX: Float,
    val translationY: Float
)

/** Movimiento secundario del arte: respiración, aleteo o zancada sobre su trayectoria principal. */
internal data class GiftArticulationTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val translationY: Float = 0f
)

internal fun giftArticulationTransform(
    profile: GiftMotionProfile,
    progress: Float,
    animationsEnabled: Boolean = true
): GiftArticulationTransform {
    if (!animationsEnabled) return GiftArticulationTransform()
    val p = progress.coerceIn(0f, 1f)
    val flutter = sin(p * PI * 28).toFloat()
    val stride = abs(sin(p * PI * 18).toFloat())
    val breathe = sin(p * PI * 6).toFloat()
    return when (profile) {
        GiftMotionProfile.BUTTERFLY,
        GiftMotionProfile.HUMMINGBIRD -> GiftArticulationTransform(
            scaleX = 0.90f + abs(flutter) * 0.12f,
            scaleY = 1.04f - abs(flutter) * 0.08f,
            rotation = flutter * 2.8f,
            translationY = -abs(flutter) * 0.025f
        )
        GiftMotionProfile.PHOENIX,
        GiftMotionProfile.DRAGON -> GiftArticulationTransform(
            scaleX = 0.94f + abs(flutter) * 0.10f,
            scaleY = 1.02f - abs(flutter) * 0.05f,
            rotation = flutter * 3.8f,
            translationY = -abs(flutter) * 0.035f
        )
        GiftMotionProfile.FOX,
        GiftMotionProfile.GUARDIAN -> GiftArticulationTransform(
            scaleX = 1.02f + stride * 0.035f,
            scaleY = 1.02f - stride * 0.055f,
            rotation = sin(p * PI * 18).toFloat() * 1.8f,
            translationY = -stride * 0.055f
        )
        GiftMotionProfile.TRAIN -> GiftArticulationTransform(
            scaleX = 1f + stride * 0.015f,
            scaleY = 1f - stride * 0.025f,
            rotation = sin(p * PI * 24).toFloat() * 0.7f,
            translationY = -stride * 0.020f
        )
        GiftMotionProfile.JELLYFISH,
        GiftMotionProfile.WHALE,
        GiftMotionProfile.ISLAND -> GiftArticulationTransform(
            scaleX = 1f + breathe * 0.025f,
            scaleY = 1f - breathe * 0.035f,
            rotation = breathe * 1.2f,
            translationY = breathe * 0.020f
        )
        GiftMotionProfile.WORLD,
        GiftMotionProfile.KINGDOM,
        GiftMotionProfile.GENESIS -> GiftArticulationTransform(
            scaleX = 1f + abs(breathe) * 0.045f,
            scaleY = 1f + abs(breathe) * 0.045f,
            rotation = breathe * 1.4f
        )
        else -> GiftArticulationTransform(
            scaleX = 1f + breathe * 0.018f,
            scaleY = 1f + breathe * 0.018f,
            rotation = breathe * 0.8f
        )
    }
}

internal fun giftMotionProfile(visualKey: String): GiftMotionProfile = when (visualKey) {
    "aurora-drop" -> GiftMotionProfile.DROP
    "prism-spark" -> GiftMotionProfile.SPARK
    "luminous-butterfly" -> GiftMotionProfile.BUTTERFLY
    "comet-flower" -> GiftMotionProfile.FLOWER
    "crystal-moon" -> GiftMotionProfile.MOON
    "solar-hummingbird" -> GiftMotionProfile.HUMMINGBIRD
    "nebula-jellyfish" -> GiftMotionProfile.JELLYFISH
    "boreal-fox" -> GiftMotionProfile.FOX
    "cosmic-whale" -> GiftMotionProfile.WHALE
    "star-garden" -> GiftMotionProfile.GARDEN
    "crystal-orchid" -> GiftMotionProfile.ORCHID
    "prism-ship" -> GiftMotionProfile.SHIP
    "forest-guardian" -> GiftMotionProfile.GUARDIAN
    "aurora-phoenix" -> GiftMotionProfile.PHOENIX
    "star-train" -> GiftMotionProfile.TRAIN
    "celestial-dragon" -> GiftMotionProfile.DRAGON
    "floating-island" -> GiftMotionProfile.ISLAND
    "celestial-world" -> GiftMotionProfile.WORLD
    "light-opera" -> GiftMotionProfile.OPERA
    "aurora-palace" -> GiftMotionProfile.PALACE
    "time-kingdom" -> GiftMotionProfile.KINGDOM
    "galactic-ascent" -> GiftMotionProfile.ASCENT
    "aurora-genesis" -> GiftMotionProfile.GENESIS
    else -> GiftMotionProfile.SEED
}

internal fun giftSceneSpec(profile: GiftMotionProfile): GiftSceneSpec = when (profile) {
    GiftMotionProfile.SEED -> GiftSceneSpec(GiftSceneEffect.GERMINATION, GiftSoundProfile.SEED_CHIME, 0.72f, 8, 1, false)
    GiftMotionProfile.DROP -> GiftSceneSpec(GiftSceneEffect.RAIN_RIPPLE, GiftSoundProfile.WATER_DROP, 0.76f, 10, 1, false)
    GiftMotionProfile.SPARK -> GiftSceneSpec(GiftSceneEffect.PRISM_BURST, GiftSoundProfile.PRISM_TWINKLE, 0.78f, 12, 1, false)
    GiftMotionProfile.BUTTERFLY -> GiftSceneSpec(GiftSceneEffect.WING_TRAIL, GiftSoundProfile.SOFT_WINGS, 0.82f, 14, 1, false)
    GiftMotionProfile.FLOWER -> GiftSceneSpec(GiftSceneEffect.PETAL_COMET, GiftSoundProfile.BLOOM, 0.84f, 16, 1, false)
    GiftMotionProfile.MOON -> GiftSceneSpec(GiftSceneEffect.LUNAR_ORBITS, GiftSoundProfile.LUNAR_ORBIT, 0.88f, 16, 2, false)
    GiftMotionProfile.HUMMINGBIRD -> GiftSceneSpec(GiftSceneEffect.GLITTER_DUST, GiftSoundProfile.HUMMINGBIRD_FLIGHT, 0.82f, 24, 2, false)
    GiftMotionProfile.JELLYFISH -> GiftSceneSpec(GiftSceneEffect.NEBULA_TENTACLES, GiftSoundProfile.NEBULA_CURRENT, 0.88f, 18, 2, false)
    GiftMotionProfile.FOX -> GiftSceneSpec(GiftSceneEffect.AURORA_PAWS, GiftSoundProfile.AURORA_PAWS, 0.91f, 18, 2, false)
    GiftMotionProfile.WHALE -> GiftSceneSpec(GiftSceneEffect.COSMIC_WAKE, GiftSoundProfile.COSMIC_WHALE, 0.96f, 20, 2, false)
    GiftMotionProfile.GARDEN -> GiftSceneSpec(GiftSceneEffect.BLOOM_FIELD, GiftSoundProfile.STAR_GARDEN, 0.96f, 22, 2, false)
    GiftMotionProfile.ORCHID -> GiftSceneSpec(GiftSceneEffect.CRYSTAL_PETALS, GiftSoundProfile.CRYSTAL_ORCHID, 0.98f, 22, 2, false)
    GiftMotionProfile.SHIP -> GiftSceneSpec(GiftSceneEffect.ENGINE_TRAIL, GiftSoundProfile.PRISM_LAUNCH, 1.00f, 24, 2, false)
    GiftMotionProfile.GUARDIAN -> GiftSceneSpec(GiftSceneEffect.FOREST_RUNES, GiftSoundProfile.GUARDIAN_GALLOP, 1.04f, 24, 2, false)
    GiftMotionProfile.PHOENIX -> GiftSceneSpec(GiftSceneEffect.AURORA_FLAMES, GiftSoundProfile.PHOENIX_RISE, 1.06f, 26, 3, false)
    GiftMotionProfile.TRAIN -> GiftSceneSpec(GiftSceneEffect.STELLAR_RAIL, GiftSoundProfile.STAR_TRAIN, 1.12f, 28, 3, false)
    GiftMotionProfile.DRAGON -> GiftSceneSpec(GiftSceneEffect.CELESTIAL_ARC, GiftSoundProfile.CELESTIAL_DRAGON, 1.12f, 30, 3, true)
    GiftMotionProfile.ISLAND -> GiftSceneSpec(GiftSceneEffect.CLOUD_ISLAND, GiftSoundProfile.FLOATING_ISLAND, 1.15f, 30, 3, true)
    GiftMotionProfile.WORLD -> GiftSceneSpec(GiftSceneEffect.PLANET_RINGS, GiftSoundProfile.CELESTIAL_WORLD, 1.18f, 32, 3, true)
    GiftMotionProfile.OPERA -> GiftSceneSpec(GiftSceneEffect.LIGHT_CURTAINS, GiftSoundProfile.LIGHT_OPERA, 1.18f, 34, 4, true)
    GiftMotionProfile.PALACE -> GiftSceneSpec(GiftSceneEffect.PALACE_BEAMS, GiftSoundProfile.AURORA_PALACE, 1.20f, 36, 4, true)
    GiftMotionProfile.KINGDOM -> GiftSceneSpec(GiftSceneEffect.CLOCKWORK_RINGS, GiftSoundProfile.TIME_KINGDOM, 1.22f, 38, 4, true)
    GiftMotionProfile.ASCENT -> GiftSceneSpec(GiftSceneEffect.GALAXY_LIFT, GiftSoundProfile.GALACTIC_ASCENT, 1.26f, 42, 5, true)
    GiftMotionProfile.GENESIS -> GiftSceneSpec(GiftSceneEffect.GENESIS_WAVE, GiftSoundProfile.AURORA_GENESIS, 1.34f, 48, 5, true)
}

internal fun giftMotionTransform(
    profile: GiftMotionProfile,
    progress: Float,
    animationsEnabled: Boolean = true
): GiftMotionTransform {
    if (!animationsEnabled) return GiftMotionTransform(1f, 0f, 0f, 0f)
    val p = progress.coerceIn(0f, 1f)
    val entry = smoothStep((p / 0.18f).coerceIn(0f, 1f))
    val pulse = sin(p * PI * 4).toFloat()
    val fastPulse = sin(p * PI * 12).toFloat()
    return when (profile) {
        GiftMotionProfile.SEED -> GiftMotionTransform(0.22f + entry * 0.78f + abs(pulse) * 0.04f, pulse * 2f, 0f, 0.52f * (1f - entry))
        GiftMotionProfile.DROP -> GiftMotionTransform(0.70f + entry * 0.30f, pulse * 2f, 0f, -0.92f + 0.92f * entry + abs(pulse) * 0.025f)
        GiftMotionProfile.SPARK -> GiftMotionTransform(0.20f + entry * 0.80f + abs(fastPulse) * 0.06f, p * 150f, 0f, 0f)
        GiftMotionProfile.BUTTERFLY -> GiftMotionTransform(0.72f + entry * 0.28f, fastPulse * 5f, -0.72f + 1.44f * p, sin(p * PI * 4).toFloat() * 0.22f - 0.08f)
        GiftMotionProfile.FLOWER -> GiftMotionTransform(0.25f + entry * 0.75f, p * 42f, sin(p * PI * 2).toFloat() * 0.08f, 0.62f * (1f - entry))
        GiftMotionProfile.MOON -> GiftMotionTransform(0.76f + entry * 0.24f, p * 34f, cos(p * PI * 2).toFloat() * 0.34f, sin(p * PI * 2).toFloat() * 0.18f)
        GiftMotionProfile.HUMMINGBIRD -> GiftMotionTransform(0.74f + entry * 0.26f + abs(fastPulse) * 0.025f, fastPulse * 3f, -1.28f + 2.56f * p, -0.18f + sin(p * PI * 6).toFloat() * 0.20f)
        GiftMotionProfile.JELLYFISH -> GiftMotionTransform(0.78f + entry * 0.22f + pulse * 0.025f, fastPulse * 1.5f, sin(p * PI * 3).toFloat() * 0.18f, 0.94f - 1.58f * p)
        GiftMotionProfile.FOX -> GiftMotionTransform(0.78f + entry * 0.22f, fastPulse * 2.5f, -1.24f + 2.48f * p, 0.30f - abs(fastPulse) * 0.10f)
        GiftMotionProfile.WHALE -> GiftMotionTransform(0.80f + entry * 0.20f, pulse * 4f, -1.24f + 2.48f * p, 0.25f - sin(p * PI).toFloat() * 0.62f)
        GiftMotionProfile.GARDEN -> GiftMotionTransform(0.22f + entry * 0.78f + abs(pulse) * 0.03f, pulse * 2f, 0f, 0.58f * (1f - entry))
        GiftMotionProfile.ORCHID -> GiftMotionTransform(0.18f + entry * 0.82f, pulse * 5f, sin(p * PI * 4).toFloat() * 0.07f, -abs(pulse) * 0.035f)
        GiftMotionProfile.SHIP -> GiftMotionTransform(0.72f + entry * 0.28f, -28f + entry * 28f, -0.92f + 1.84f * p, 0.86f - 1.72f * p)
        GiftMotionProfile.GUARDIAN -> GiftMotionTransform(0.80f + entry * 0.20f, fastPulse * 1.8f, -1.30f + 2.60f * p, 0.32f - abs(fastPulse) * 0.13f)
        GiftMotionProfile.PHOENIX -> GiftMotionTransform(0.62f + entry * 0.38f, pulse * 7f, sin(p * PI * 2).toFloat() * 0.35f, 0.92f - 1.55f * smoothStep(p))
        GiftMotionProfile.TRAIN -> GiftMotionTransform(0.82f + entry * 0.18f, pulse * 1.2f, -1.48f + 2.96f * smoothStep(p), 0.34f + fastPulse * 0.012f)
        GiftMotionProfile.DRAGON -> GiftMotionTransform(0.66f + entry * 0.34f, 14f * cos(p * PI * 2).toFloat(), -1.18f + 2.36f * p, sin(p * PI * 3).toFloat() * 0.43f - 0.12f)
        GiftMotionProfile.ISLAND -> GiftMotionTransform(0.56f + entry * 0.44f, pulse * 1.5f, sin(p * PI * 2).toFloat() * 0.05f, 0.56f * (1f - entry) + pulse * 0.045f)
        GiftMotionProfile.WORLD -> GiftMotionTransform(0.38f + entry * 0.62f, p * 42f, 0f, 0f)
        GiftMotionProfile.OPERA -> GiftMotionTransform(0.52f + entry * 0.48f, pulse * 4f, sin(p * PI * 2).toFloat() * 0.28f, -abs(sin(p * PI).toFloat()) * 0.16f)
        GiftMotionProfile.PALACE -> GiftMotionTransform(0.50f + entry * 0.50f, 0f, 0f, 0.78f * (1f - entry))
        GiftMotionProfile.KINGDOM -> GiftMotionTransform(0.36f + entry * 0.64f, p * 20f, cos(p * PI * 2).toFloat() * 0.09f, sin(p * PI * 2).toFloat() * 0.06f)
        GiftMotionProfile.ASCENT -> GiftMotionTransform(0.40f + entry * 0.60f, pulse * 3f, sin(p * PI * 5).toFloat() * 0.12f, 1.18f - 2.36f * smoothStep(p))
        GiftMotionProfile.GENESIS -> GiftMotionTransform(0.10f + entry * 0.90f + abs(pulse) * 0.05f, p * 120f, 0f, 0f)
    }
}

internal fun giftAnimationDurationMs(priceDiamonds: Int): Int {
    val price = priceDiamonds.coerceAtLeast(0)
    return when (giftAnimationTier(price)) {
        0 -> (700 + price / 100f * 500).toInt().coerceIn(700, 1_200)
        1 -> (1_200 + (price - 100) / 800f * 1_200).toInt().coerceIn(1_200, 2_400)
        else -> (2_600 + (price - 900) / 4_100f * 2_400).toInt().coerceIn(2_600, 5_000)
    }
}

internal fun giftAnimationTier(priceDiamonds: Int): Int = when {
    priceDiamonds < 100 -> 0
    priceDiamonds < 900 -> 1
    else -> 2
}

private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
