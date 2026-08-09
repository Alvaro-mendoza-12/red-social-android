package conectate.com.presentation.wallet

import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.NOVVIN_CANONICAL_GIFT_CATALOG
import conectate.com.data.model.NOVVIN_GIFT_VISUAL_KEYS
import conectate.com.data.model.VirtualGift
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftAnimationTierTest {
    @Test
    fun pricesChooseMicroHighlightAndCelebrationTiers() {
        assertEquals(0, giftAnimationTier(5))
        assertEquals(0, giftAnimationTier(99))
        assertEquals(1, giftAnimationTier(100))
        assertEquals(1, giftAnimationTier(899))
        assertEquals(2, giftAnimationTier(900))
        assertEquals(2, giftAnimationTier(5_000))
    }

    @Test
    fun tiersStayInsideTheDocumentedAnimationWindows() {
        assertTrue(giftAnimationDurationMs(5) in 700..1_200)
        assertTrue(giftAnimationDurationMs(100) in 1_200..2_400)
        assertTrue(giftAnimationDurationMs(900) in 2_500..5_000)
    }

    @Test
    fun canonicalDurationsMatchTheVersionedManifestPolicy() {
        val expected = listOf(
            725, 750, 775, 825, 900, 1_000, 1_150, 1_245,
            1_305, 1_350, 1_425, 1_500, 1_575, 1_725, 1_950, 2_175,
            2_600, 2_717, 2_892, 3_126, 3_360, 3_712, 4_180, 5_000
        )

        assertEquals(
            expected,
            NOVVIN_CANONICAL_GIFT_CATALOG.map { giftAnimationDurationMs(it.priceDiamonds) }
        )
    }

    @Test
    fun everyCanonicalArtworkHasItsOwnMotionProfile() {
        val profiles = NOVVIN_GIFT_VISUAL_KEYS.map(::giftMotionProfile)
        assertEquals(NOVVIN_GIFT_VISUAL_KEYS.size, profiles.toSet().size)
        assertEquals(profiles.size, profiles.map(::giftSceneSpec).map { it.effect }.toSet().size)
        assertEquals(profiles.size, profiles.map(::giftSceneSpec).map { it.sound }.toSet().size)
    }

    @Test
    fun celebrationQueueKeepsOneProtagonistAndTwoWaiting() {
        val queue = GiftEventQueue(capacity = 3)
        val events = (1..4).map(::event)

        queue.offerAll(events)

        assertEquals(3, queue.size())
        assertEquals("transaction-1", queue.peek()?.transactionId)
        queue.complete("transaction-1")
        queue.offerAll(events)
        assertEquals("transaction-2", queue.peek()?.transactionId)
        queue.complete("transaction-2")
        assertEquals("transaction-3", queue.peek()?.transactionId)
        queue.complete("transaction-3")
        assertEquals("transaction-4", queue.peek()?.transactionId)
        queue.complete("transaction-4")
        assertNull(queue.peek())
    }

    @Test
    fun semanticCharactersTravelAcrossTheirScene() {
        val hummingbirdStart = giftMotionTransform(GiftMotionProfile.HUMMINGBIRD, 0.05f)
        val hummingbirdEnd = giftMotionTransform(GiftMotionProfile.HUMMINGBIRD, 0.95f)
        assertTrue(hummingbirdStart.translationX < -1f)
        assertTrue(hummingbirdEnd.translationX > 1f)
        assertEquals(GiftSceneEffect.GLITTER_DUST, giftSceneSpec(GiftMotionProfile.HUMMINGBIRD).effect)

        val trainStart = giftMotionTransform(GiftMotionProfile.TRAIN, 0f)
        val trainEnd = giftMotionTransform(GiftMotionProfile.TRAIN, 1f)
        assertTrue(trainStart.translationX < -1.4f)
        assertTrue(trainEnd.translationX > 1.4f)
        assertEquals(GiftSoundProfile.STAR_TRAIN, giftSceneSpec(GiftMotionProfile.TRAIN).sound)

        val guardianStart = giftMotionTransform(GiftMotionProfile.GUARDIAN, 0f)
        val guardianEnd = giftMotionTransform(GiftMotionProfile.GUARDIAN, 1f)
        assertTrue(guardianStart.translationX < -1.2f)
        assertTrue(guardianEnd.translationX > 1.2f)
        assertTrue(giftMotionTransform(GiftMotionProfile.GUARDIAN, 0.125f).translationY < 0.32f)
    }

    @Test
    fun creatureArtworkHasArticulatedMotionIndependentFromItsPath() {
        val birdA = giftArticulationTransform(GiftMotionProfile.HUMMINGBIRD, 0.10f)
        val birdB = giftArticulationTransform(GiftMotionProfile.HUMMINGBIRD, 0.125f)
        val guardian = giftArticulationTransform(GiftMotionProfile.GUARDIAN, 0.125f)
        val staticBird = giftArticulationTransform(
            GiftMotionProfile.HUMMINGBIRD,
            progress = 0.125f,
            animationsEnabled = false
        )

        assertTrue(birdA.scaleX != birdB.scaleX || birdA.rotation != birdB.rotation)
        assertTrue(guardian.translationY < 0f)
        assertEquals(GiftArticulationTransform(), staticBird)
    }

    @Test
    fun premiumCelebrationsUseMoreLayersAndFullScreen() {
        val micro = giftSceneSpec(GiftMotionProfile.SEED)
        val premium = giftSceneSpec(GiftMotionProfile.GENESIS)

        assertFalse(micro.fullScreen)
        assertTrue(premium.fullScreen)
        assertTrue(premium.layerCount > micro.layerCount)
        assertTrue(premium.particleCount > micro.particleCount)
        assertTrue(giftSoundPlan(premium.sound).durationMs > giftSoundPlan(micro.sound).durationMs)
        assertTrue(giftSoundPlan(premium.sound).voices > giftSoundPlan(micro.sound).voices)
    }

    @Test
    fun qualityBudgetReducesCostButKeepsTheGiftScene() {
        val original = giftSceneSpec(GiftMotionProfile.GENESIS)
        val medium = giftSceneForQuality(original, GiftRenderQuality.MEDIUM)
        val low = giftSceneForQuality(original, GiftRenderQuality.LOW)

        assertEquals(original.effect, medium.effect)
        assertEquals(original.sound, low.sound)
        assertTrue(medium.particleCount < original.particleCount)
        assertTrue(low.particleCount < medium.particleCount)
        assertTrue(low.fullScreen)
    }

    @Test
    fun reducedMotionUsesAStableCenteredFallback() {
        GiftMotionProfile.entries.forEach { profile ->
            assertEquals(
                GiftMotionTransform(1f, 0f, 0f, 0f),
                giftMotionTransform(profile, progress = 0.74f, animationsEnabled = false)
            )
        }
    }

    @Test
    fun proceduralSoundIsDeterministicAndHasSoftEdges() {
        val plan = giftSoundPlan(GiftSoundProfile.HUMMINGBIRD_FLIGHT)
        val first = synthesizeGiftPcm(GiftSoundProfile.HUMMINGBIRD_FLIGHT, plan, sampleRate = 8_000)
        val second = synthesizeGiftPcm(GiftSoundProfile.HUMMINGBIRD_FLIGHT, plan, sampleRate = 8_000)

        assertArrayEquals(first, second)
        assertEquals(plan.durationMs * 8, first.size)
        assertEquals(0, first.first().toInt())
        assertTrue(first.any { it.toInt() != 0 })
        assertTrue(kotlin.math.abs(first.last().toInt()) < 1_000)
    }

    @Test
    fun soundPlaybackGateInvalidatesInFlightStartsAndDrainsOnce() {
        val gate = GiftSoundPlaybackGate<Any>()
        val first = Any()
        val second = Any()
        val initialGeneration = gate.currentGeneration()

        assertTrue(gate.tryRegister(initialGeneration, first))
        assertEquals(listOf(first), gate.invalidateAndDrain())
        assertFalse(gate.tryRegister(initialGeneration, second))

        val nextGeneration = gate.currentGeneration()
        assertTrue(gate.tryRegister(nextGeneration, second))
        assertTrue(gate.unregister(second))
        assertFalse(gate.unregister(second))
        assertEquals(emptyList<Any>(), gate.invalidateAndDrain())
    }

    private fun event(index: Int) = LiveGiftEvent(
        transactionId = "transaction-$index",
        liveStreamId = "live",
        senderId = "sender",
        senderName = "Usuario",
        receiverId = "receiver",
        gift = VirtualGift(
            id = "gift-$index",
            name = "Regalo $index",
            imageUrl = "conectate://gift-assets/pulse",
            priceDiamonds = 5
        )
    )
}
