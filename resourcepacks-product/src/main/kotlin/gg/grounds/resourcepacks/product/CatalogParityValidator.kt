package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackPath
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetKind

/**
 * Verifies the catalog-to-content contract. The mapping is deliberately local: publishing content
 * paths is a product concern, not a scene-format API concern.
 */
internal object CatalogParityValidator {
    fun validate(catalog: AssetCatalog, contentEntries: Set<PackPath>): ProductValidationResult {
        val expected =
            catalog.assets
                .map { (key, definition) ->
                    expectedPath(key.value, definition.kind) to definition.kind
                }
                .toMap()
        val problems = mutableListOf<ProductProblem>()
        // Only these four projected roots are catalog assets. Fonts, language files, shaders and
        // other legitimate content remain product-owned and deliberately do not require catalog
        // entries. Future asset kinds must add one projection here and its corresponding test.
        val projectedEntries = contentEntries.filter(::isProjectedAssetPath).toSet()
        val wrongKindEntries =
            projectedEntries
                .filter { actual ->
                    actual !in expected &&
                        expected.keys.any { expectedPath ->
                            expectedPath.fileNameStem() == actual.fileNameStem()
                        }
                }
                .toSet()
        expected.keys
            .filter { expectedPath ->
                expectedPath !in contentEntries &&
                    projectedEntries.none { actual ->
                        actual in wrongKindEntries &&
                            actual.fileNameStem() == expectedPath.fileNameStem()
                    }
            }
            .sorted()
            .forEach { path ->
                problems +=
                    ProductProblem(ProductProblemCode.CATALOG_MISSING_CONTENT, path = path.value)
            }
        wrongKindEntries.sorted().forEach { path ->
            problems += ProductProblem(ProductProblemCode.CATALOG_WRONG_KIND, path = path.value)
        }
        projectedEntries.subtract(expected.keys).subtract(wrongKindEntries).sorted().forEach { path
            ->
            problems += ProductProblem(ProductProblemCode.CATALOG_EXTRA_CONTENT, path = path.value)
        }
        return ProductValidationResult(problems)
    }

    private fun expectedPath(key: String, kind: AssetKind): PackPath {
        val localKey = key.substringAfter(':', key)
        val prefix =
            when (kind) {
                AssetKind.PROP,
                AssetKind.NPC_BODY -> "models/npc_bodies"
                AssetKind.SOUND -> "sounds"
                AssetKind.PARTICLE -> "particles"
            }
        val suffix = if (kind == AssetKind.SOUND) "ogg" else "json"
        return PackPath.of("assets/grounds/$prefix/$localKey.$suffix")
    }

    private fun PackPath.fileNameStem(): String =
        value.substringAfterLast('/').substringBeforeLast('.')

    private fun isProjectedAssetPath(path: PackPath): Boolean =
        path.value.startsWith("assets/grounds/models/") ||
            path.value.startsWith("assets/grounds/sounds/") ||
            path.value.startsWith("assets/grounds/particles/")
}
