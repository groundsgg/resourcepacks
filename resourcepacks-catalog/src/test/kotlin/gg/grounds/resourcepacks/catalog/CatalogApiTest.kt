package gg.grounds.resourcepacks.catalog

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogApiTest {
    @Test
    fun `catalog exposes the fixed GUI identifiers`() {
        assertEquals("grounds", GroundsGuiIds.NAMESPACE)
        assertEquals("menu", GroundsGuiIds.PANEL_MENU)
        assertEquals("close", GroundsGuiIds.ICON_CLOSE)
        assertEquals("back", GroundsGuiIds.ICON_BACK)
        assertEquals("next", GroundsGuiIds.ICON_NEXT)
        assertEquals("blank", GroundsGuiIds.ICON_BLANK)
        assertEquals("default", GroundsGuiIds.TOOLTIP_DEFAULT)
        assertEquals("hover", GroundsGuiIds.FRAME_HOVER)
    }

    @Test
    fun `catalog has the fixed identity and version compatibility`() {
        val catalog = GroundsAssetCatalog.catalog

        assertEquals("grounds:assets", catalog.id.value)
        assertEquals(versionFromBuild(), catalog.version)
        assertEquals("grounds:resourcepacks", catalog.resourcePackCompatibility.catalog.value)
        assertEquals(versionFromBuild(), catalog.resourcePackCompatibility.minInclusive)
        assertEquals(versionFromBuild(), catalog.resourcePackCompatibility.maxInclusive)
    }

    @Test
    fun `catalog declares ordered immutable scene editor bootstrap assets`() {
        val catalog = GroundsAssetCatalog.catalog
        val expectedKeys =
            listOf(AssetKey("grounds:editor/marker"), AssetKey("grounds:editor/guide"))

        assertEquals(expectedKeys, GroundsAssets.all.toList())
        assertEquals(expectedKeys, catalog.assets.keys.toList())
        assertEquals(AssetKind.PROP, catalog.assets.getValue(expectedKeys[0]).kind)
        assertEquals(
            LocalBounds(Vec3(0.0, 0.5, 0.0), Vec3(1.0, 1.0, 1.0)),
            catalog.assets.getValue(expectedKeys[0]).defaultBounds,
        )
        assertEquals(emptySet(), catalog.assets.getValue(expectedKeys[0]).animations)
        assertEquals(emptyMap(), catalog.assets.getValue(expectedKeys[0]).editorMetadata)
        assertEquals(AssetKind.NPC_BODY, catalog.assets.getValue(expectedKeys[1]).kind)
        assertEquals(
            LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)),
            catalog.assets.getValue(expectedKeys[1]).defaultBounds,
        )
        assertEquals(emptySet(), catalog.assets.getValue(expectedKeys[1]).animations)
        assertEquals(emptyMap(), catalog.assets.getValue(expectedKeys[1]).editorMetadata)

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (GroundsAssets.all as MutableSet<Any?>).add("grounds:unexpected")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (catalog.assets as MutableMap<Any?, Any?>)["grounds:unexpected"] = Any()
        }
    }

    @Test
    fun `theme declares the final Grounds GUI contract`() {
        val theme = GroundsGuiTheme.theme

        assertEquals("grounds", theme.namespace)
        assertEquals(88, theme.packFormat.minInclusive)
        assertEquals(88, theme.packFormat.maxInclusive)
        assertEquals("Grounds platform UI", theme.description)
        assertEquals(listOf("menu"), theme.panels.map { it.id })
        assertEquals(listOf("close", "back", "next", "blank"), theme.icons.map { it.id })
        assertEquals(listOf("default"), theme.tooltips.map { it.id })
        assertEquals(listOf("hover"), theme.frames.map { it.id })
    }

    private fun versionFromBuild(): String = System.getProperty("catalog.version")
}
