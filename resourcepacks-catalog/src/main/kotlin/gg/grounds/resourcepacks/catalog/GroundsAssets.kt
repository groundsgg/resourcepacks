package gg.grounds.resourcepacks.catalog

import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3
import java.util.Collections

object GroundsAssets {
    val all: Set<AssetKey> =
        Collections.unmodifiableSet(
            linkedSetOf(*GroundsAssetCatalog.catalog.assets.keys.toTypedArray())
        )
}

object GroundsAssetCatalog {
    private val definitions: Map<AssetKey, AssetDefinition> =
        Collections.unmodifiableMap(
            linkedMapOf(
                AssetKey("grounds:editor/marker") to
                    AssetDefinition(
                        AssetKey("grounds:editor/marker"),
                        AssetKind.PROP,
                        emptySet(),
                        LocalBounds(Vec3(0.0, 0.5, 0.0), Vec3(1.0, 1.0, 1.0)),
                        emptyMap(),
                    ),
                AssetKey("grounds:editor/guide") to
                    AssetDefinition(
                        AssetKey("grounds:editor/guide"),
                        AssetKind.NPC_BODY,
                        emptySet(),
                        LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)),
                        emptyMap(),
                    ),
            )
        )
    private val catalogVersion: String =
        checkNotNull(
                GroundsAssetCatalog::class
                    .java
                    .getResourceAsStream("/gg/grounds/resourcepacks/catalog/catalog-version.txt")
            ) {
                "Missing generated catalog version resource."
            }
            .bufferedReader()
            .use { it.readText().trim() }

    val catalog: AssetCatalog =
        AssetCatalog(
            CatalogId("grounds:assets"),
            catalogVersion,
            CatalogVersionRange(CatalogId("grounds:resourcepacks"), catalogVersion, catalogVersion),
            definitions,
        )
}
