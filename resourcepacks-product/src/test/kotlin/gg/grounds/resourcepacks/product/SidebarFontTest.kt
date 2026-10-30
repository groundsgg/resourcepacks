package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidebarFontTest {
    @Test
    fun `cap stays above the native title and bar spans the two native text lines`() {
        val logo = java.awt.image.BufferedImage(96, 19, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val textures = SidebarFont.textures(logo)
        assertTrue("sidebar_cap.png" !in textures)
        val logoTexture = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_logo.png")))
        assertEquals(216, logoTexture.width)
        assertEquals(78, logoTexture.height)
        val bar = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_bar.png")))
        assertEquals(1, bar.width)
        assertEquals(18, bar.height)
        assertTrue((0 until 18).all { bar.getRGB(0, it) == -1 })
        listOf("crown", "coin", "friends").forEach { name ->
            val icon = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_$name.png")))
            assertEquals(30, icon.width)
            assertEquals(30, icon.height)
            assertTrue((0 until 30).any { y -> icon.getRGB(29, y) ushr 24 != 0 })
        }
        val separator =
            ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_separator.png")))
        assertEquals(180, separator.width)
        assertEquals(30, separator.height)
        assertEquals(-1, separator.getRGB(0, 8))
        val font = SidebarFont.json()
        assertTrue("\"\\uE300\":111" in font)
        assertTrue("\"height\":26,\"ascent\":18" in font)
        assertTrue("sidebar_logo.png" in font)
        assertTrue("\\uE300" in font)
        assertTrue("\\uE301" in font)
        assertTrue("\"\\uE310\":1" in font)
        assertTrue("\"\\uE311\":-1" in font)
    }

    @Test
    fun `footer references vanilla ascii with a four pixel vertical offset`() {
        val footer = SidebarFont.footerJson()
        assertTrue("minecraft:font/ascii.png" in footer)
        assertTrue("\"height\":8,\"ascent\":3" in footer)
    }
}
