package gg.grounds.resourcepacks.catalog

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CatalogImmutabilityTest {
    @Test
    fun `public asset collections reject mutation`() {
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (GroundsAssets.all as MutableSet<Any?>).add("grounds:unexpected")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (GroundsAssetCatalog.catalog.assets as MutableMap<Any?, Any?>)["grounds:unexpected"] = Any()
        }
    }
}
