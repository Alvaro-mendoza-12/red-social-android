package conectate.com.data.model

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftCatalogTest {
    @Test
    fun legacyNamesResolveToDistinctOriginalArtwork() {
        assertEquals("starlight-seed", gift("a", "Corazón", null).visualKey())
        assertEquals("comet-flower", gift("b", "Rosa", null).visualKey())
        assertEquals("prism-spark", gift("c", "Trofeo", null).visualKey())
        assertEquals("star-garden", gift("d", "Fuegos Artificiales", null).visualKey())
        assertEquals("boreal-fox", gift("e", "Superestrella", null).visualKey())
    }

    @Test
    fun assetKeyWinsOverBackendUuid() {
        val value = gift(
            id = "7a100024-0000-4000-8000-000000000024",
            name = "Nombre remoto",
            imageUrl = "conectate://gift-assets/aurora-genesis"
        )
        assertEquals("aurora-genesis", value.visualKey())
    }

    @Test
    fun duplicatedLegacyRowsCollapseToOneVisualFamily() {
        val catalog = listOf(
            gift("legacy-1", "Corazon", null, 10),
            gift("legacy-2", "Corazón", null, 10),
            gift("builtin-heart", "Pulso Novvin", "conectate://gift-assets/pulse", 5)
        ).withoutVisualDuplicates()

        assertEquals(1, catalog.size)
        assertEquals("builtin-heart", catalog.single().id)
        assertTrue(NOVVIN_GIFT_VISUAL_KEYS.size >= 16)
    }

    @Test
    fun remoteCatalogIsPreservedAndCompletedToAllVisualFamilies() {
        val remote = NOVVIN_GIFT_VISUAL_KEYS.take(5).mapIndexed { index, visual ->
            gift("remote-$index", "Remoto $index", "conectate://gift-assets/$visual", index + 1)
        }
        val fallback = NOVVIN_GIFT_VISUAL_KEYS.mapIndexed { index, visual ->
            gift("fallback-$index", "Fallback $index", "conectate://gift-assets/$visual", index + 1)
        }

        val merged = mergeGiftCatalog(remote, fallback)

        assertEquals(NOVVIN_GIFT_VISUAL_KEYS.size, merged.size)
        assertTrue(remote.all { expected -> merged.any { it.id == expected.id } })
        assertEquals(NOVVIN_GIFT_VISUAL_KEYS.toSet(), merged.map { it.visualKey() }.toSet())
    }

    @Test
    fun catalogExposesFourCategoriesAndThreeTiers() {
        val catalog = NOVVIN_GIFT_VISUAL_KEYS.mapIndexed { index, visual ->
            gift(
                id = "gift-$index",
                name = visual,
                imageUrl = "conectate://gift-assets/$visual",
                price = when (index) {
                    in 0..3 -> 10
                    in 4..8 -> 100
                    else -> 900
                }
            )
        }

        assertEquals(GiftCategory.values().toSet(), catalog.map { it.category() }.toSet())
        assertEquals(GiftTier.values().toSet(), catalog.map { it.tier() }.toSet())
    }

    @Test
    fun versionedManifestExactlyMatchesCatalogAndFinalAssets() {
        val appRoot = locateAppRoot()
        val manifest = String(
            Files.readAllBytes(appRoot.resolve("src/main/res/raw/novvin_gift_manifest_v1.json")),
            Charsets.UTF_8
        )
        val manifestKeys = Regex("\"visualKey\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()
        val catalogKeys = NOVVIN_CANONICAL_GIFT_CATALOG.map { it.visualKey() }

        assertEquals(24, catalogKeys.size)
        assertEquals(catalogKeys.size, catalogKeys.distinct().size)
        assertEquals(manifestKeys.size, manifestKeys.distinct().size)
        assertEquals(catalogKeys.toSet(), manifestKeys.toSet())
        assertEquals(
            NOVVIN_CANONICAL_GIFT_CATALOG.size,
            NOVVIN_CANONICAL_GIFT_CATALOG.map { it.id }.distinct().size
        )

        val fileAndHash = Regex(
            "\"file\":\"([^\"]+)\".*?\"sha256\":\"([0-9a-f]{64})\""
        ).findAll(manifest).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(24, fileAndHash.size)
        fileAndHash.forEach { (fileName, expectedHash) ->
            val asset = appRoot.resolve("src/main/res/drawable-nodpi").resolve(fileName)
            assertTrue("Falta el asset $fileName", Files.isRegularFile(asset))
            assertEquals(expectedHash, sha256(asset))
        }
    }

    private fun locateAppRoot(): Path = sequenceOf(
        Path.of("."),
        Path.of("app")
    ).first { Files.isRegularFile(it.resolve("src/main/res/raw/novvin_gift_manifest_v1.json")) }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    private fun gift(
        id: String,
        name: String,
        imageUrl: String?,
        price: Int = 10
    ) = VirtualGift(
        id = id,
        name = name,
        imageUrl = imageUrl,
        priceDiamonds = price,
        sortOrder = 1
    )
}
