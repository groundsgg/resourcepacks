package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidebarFontTest {
    @Test
    fun `fine opaque detail contributes its full area instead of selected samples`() {
        val source =
            java.awt.image.BufferedImage(648, 126, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        repeat(source.height) { y ->
            repeat(source.width) { x ->
                source.setRGB(x, y, if (x % 3 == 1) -1 else 0xFF000000.toInt())
            }
        }
        val logo =
            ImageIO.read(SidebarFont.textures(source).getValue("sidebar_logo.png").inputStream())
        assertEquals(0xFF555555.toInt(), logo.getRGB(100, 20))
        assertTrue((42 until 78).all { y -> (0 until 216).all { x -> logo.getRGB(x, y) == 0 } })
    }

    @Test
    fun `transparent colors cannot bleed into filtered logo edges`() {
        val source =
            java.awt.image.BufferedImage(648, 126, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        repeat(source.height) { y ->
            repeat(source.width) { x ->
                source.setRGB(
                    x,
                    y,
                    if (x % 3 == 1 && y % 3 == 1) 0xFFFF0000.toInt() else 0x000000FF,
                )
            }
        }
        val logo =
            ImageIO.read(SidebarFont.textures(source).getValue("sidebar_logo.png").inputStream())
        assertEquals(0x1CFF0000, logo.getRGB(100, 20))
    }

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
