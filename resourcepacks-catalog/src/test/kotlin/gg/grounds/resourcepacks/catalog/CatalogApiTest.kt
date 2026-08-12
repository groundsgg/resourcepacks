package gg.grounds.resourcepacks.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `bootstrap catalog has no assets`() {
        assertTrue(GroundsAssets.all.isEmpty())
        assertTrue(GroundsAssetCatalog.catalog.assets.isEmpty())
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
