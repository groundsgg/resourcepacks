package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogParityValidatorTest {
    @Test
    fun `scene editor bootstrap catalog requires both exact projected model paths`() {
        val result =
            CatalogParityValidator.validate(
                GroundsAssetCatalog.catalog,
                setOf(
                    PackPath.of("assets/grounds/models/editor/marker.json"),
                    PackPath.of("assets/grounds/models/npc_bodies/editor/guide.json"),
                ),
            )

        assertTrue(result.isValid)
        assertEquals(emptyList(), result.problems)
    }

    @Test
    fun `unmapped content entry fails catalog parity`() {
        val result =
            CatalogParityValidator.validate(
                GroundsAssetCatalog.catalog,
                sceneEditorBootstrapEntries + PackPath.of("assets/grounds/models/orphan.json"),
            )

        assertEquals(
            listOf(ProductProblemCode.CATALOG_EXTRA_CONTENT),
            result.problems.map { it.code },
        )
    }

    @Test
    fun `legitimate non asset content paths are outside catalog parity projection`() {
        val result =
            CatalogParityValidator.validate(
                GroundsAssetCatalog.catalog,
                sceneEditorBootstrapEntries + PackPath.of("assets/grounds/lang/en_us.json"),
            )

        assertTrue(result.isValid)
    }

    @Test
    fun `future fixture detects missing extra and wrong kind content mappings`() {
        val catalog =
            AssetCatalog(
                CatalogId("grounds:assets"),
                "0.1.0",
                CatalogVersionRange(CatalogId("grounds:resourcepacks"), "0.1.0", "0.1.0"),
                mapOf(
                    AssetKey("grounds:chair") to
                        AssetDefinition(
                            AssetKey("grounds:chair"),
                            AssetKind.PROP,
                            emptySet(),
                            null,
                            emptyMap(),
                        )
                ),
            )

        val missing = CatalogParityValidator.validate(catalog, emptySet())
        val extra =
            CatalogParityValidator.validate(
                catalog,
                setOf(PackPath.of("assets/grounds/models/orphan.json")),
            )
        val wrongKind =
            CatalogParityValidator.validate(
                catalog,
                setOf(PackPath.of("assets/grounds/sounds/chair.ogg")),
            )

        assertEquals(
            listOf(ProductProblemCode.CATALOG_MISSING_CONTENT),
            missing.problems.map { it.code },
        )
        assertEquals(
            listOf(
                ProductProblemCode.CATALOG_MISSING_CONTENT,
                ProductProblemCode.CATALOG_EXTRA_CONTENT,
            ),
            extra.problems.map { it.code },
        )
        assertEquals(
            listOf(ProductProblemCode.CATALOG_WRONG_KIND),
            wrongKind.problems.map { it.code },
        )
    }

    @Test
    fun `npc body uses its exact nested model path and rejects the neighboring prop path`() {
        val npcBodyCatalog = catalogWith("grounds:guide", AssetKind.NPC_BODY)

        val exact =
            CatalogParityValidator.validate(
                npcBodyCatalog,
                setOf(PackPath.of("assets/grounds/models/npc_bodies/guide.json")),
            )
        val neighboringPropPath =
            CatalogParityValidator.validate(
                npcBodyCatalog,
                setOf(PackPath.of("assets/grounds/models/guide.json")),
            )
        val propAtNpcBodyPath =
            CatalogParityValidator.validate(
                catalogWith("grounds:guide", AssetKind.PROP),
                setOf(PackPath.of("assets/grounds/models/npc_bodies/guide.json")),
            )

        assertTrue(exact.isValid)
        assertEquals(
            listOf(ProductProblemCode.CATALOG_WRONG_KIND),
            neighboringPropPath.problems.map { it.code },
        )
        assertEquals(
            listOf(ProductProblemCode.CATALOG_WRONG_KIND),
            propAtNpcBodyPath.problems.map { it.code },
        )
    }

    private fun catalogWith(key: String, kind: AssetKind): AssetCatalog {
        val assetKey = AssetKey(key)
        return AssetCatalog(
            CatalogId("grounds:assets"),
            "0.1.0",
            CatalogVersionRange(CatalogId("grounds:resourcepacks"), "0.1.0", "0.1.0"),
            mapOf(assetKey to AssetDefinition(assetKey, kind, emptySet(), null, emptyMap())),
        )
    }

    private val sceneEditorBootstrapEntries =
        setOf(
            PackPath.of("assets/grounds/models/editor/marker.json"),
            PackPath.of("assets/grounds/models/npc_bodies/editor/guide.json"),
        )
}
