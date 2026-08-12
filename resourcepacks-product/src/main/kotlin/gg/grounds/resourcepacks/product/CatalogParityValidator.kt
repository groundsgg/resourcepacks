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
        val wrongKindEntries =
            contentEntries
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
                    contentEntries.none { actual ->
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
        contentEntries.subtract(expected.keys).subtract(wrongKindEntries).sorted().forEach { path ->
            problems += ProductProblem(ProductProblemCode.CATALOG_EXTRA_CONTENT, path = path.value)
        }
        return ProductValidationResult(problems)
    }

    private fun expectedPath(key: String, kind: AssetKind): PackPath {
        val localKey = key.substringAfter(':', key)
        val prefix =
            when (kind) {
                AssetKind.PROP,
                AssetKind.NPC_BODY -> "models"
                AssetKind.SOUND -> "sounds"
                AssetKind.PARTICLE -> "particles"
            }
        val suffix = if (kind == AssetKind.SOUND) "ogg" else "json"
        return PackPath.of("assets/grounds/$prefix/$localKey.$suffix")
    }

    private fun PackPath.fileNameStem(): String =
        value.substringAfterLast('/').substringBeforeLast('.')
}
