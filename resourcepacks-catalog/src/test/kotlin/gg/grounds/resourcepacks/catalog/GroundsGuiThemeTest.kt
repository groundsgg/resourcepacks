package gg.grounds.resourcepacks.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class GroundsGuiThemeTest {
    @Test
    fun `grounds theme declares the fixed GUI contract`() {
        val theme = GroundsGuiTheme.theme

        assertEquals("grounds", theme.namespace)
        assertEquals(88, theme.packFormat.minInclusive)
        assertEquals(88, theme.packFormat.maxInclusive)
        assertEquals("Grounds platform UI", theme.description)
        assertEquals(setOf("menu"), theme.panels.map { it.id }.toSet())
        assertEquals(setOf("close", "back", "next", "blank"), theme.icons.map { it.id }.toSet())
        assertEquals(setOf("default"), theme.tooltips.map { it.id }.toSet())
        assertEquals(setOf("hover"), theme.frames.map { it.id }.toSet())

        val panel = theme.panels.single()
        assertEquals("panels/menu.png", panel.texture)
        assertEquals(176, panel.width)
        assertEquals(168, panel.height)
        assertEquals(-6, panel.offsetY)
        assertEquals(
            mapOf(
                "close" to "icons/close.png",
                "back" to "icons/back.png",
                "next" to "icons/next.png",
                "blank" to "icons/blank.png",
            ),
            theme.icons.associate { it.id to it.texture },
        )
        assertEquals("tooltips/default_bg.png", theme.tooltips.single().background)
        assertEquals("tooltips/default_frame.png", theme.tooltips.single().frame)
        assertEquals("frames/hover.png", theme.frames.single().texture)
    }
}
