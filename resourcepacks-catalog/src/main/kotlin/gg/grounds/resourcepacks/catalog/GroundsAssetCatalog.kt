package gg.grounds.resourcepacks.catalog

import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.util.Collections

object GroundsAssetCatalog {
    val catalog: AssetCatalog =
        AssetCatalog(
            CatalogId("grounds:assets"),
            CatalogBuildInfo.VERSION,
            CatalogVersionRange(
                CatalogId("grounds:resourcepacks"),
                CatalogBuildInfo.VERSION,
                CatalogBuildInfo.VERSION,
            ),
            Collections.unmodifiableMap(linkedMapOf()),
        )
}
