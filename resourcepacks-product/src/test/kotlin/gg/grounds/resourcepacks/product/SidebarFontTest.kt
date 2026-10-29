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
        val cap = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_cap.png")))
        assertEquals(180, cap.width)
        assertEquals(64, cap.height)
        assertEquals(0, cap.getRGB(0, 0))
        assertEquals(0x66000000, cap.getRGB(10, 0))
        assertEquals(0x66000000, cap.getRGB(0, 47))
        for (y in 48 until 64) for (x in 0 until 180) assertEquals(0, cap.getRGB(x, y))
        val logoTexture = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_logo.png")))
        assertEquals(216, logoTexture.width)
        assertEquals(78, logoTexture.height)
        val bar = ImageIO.read(ByteArrayInputStream(textures.getValue("sidebar_bar.png")))
        assertEquals(1, bar.width)
        assertEquals(18, bar.height)
        assertTrue((0 until 18).all { bar.getRGB(0, it) == -1 })
        val font = SidebarFont.json()
        assertTrue("\"ascent\":23" in font)
        assertTrue("\"height\":26,\"ascent\":18" in font)
        assertTrue("sidebar_logo.png" in font)
        assertTrue("\\uE300" in font)
        assertTrue("\\uE301" in font)
        assertTrue("\"\\uE310\":1" in font)
        assertTrue("\"\\uE311\":-1" in font)
    }

    @Test
    fun `logo cap has one antialiased round corner instead of a three pixel step`() {
        val logo = java.awt.image.BufferedImage(96, 19, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val cap =
            ImageIO.read(
                ByteArrayInputStream(SidebarFont.textures(logo).getValue("sidebar_cap.png"))
            )
        assertEquals(0, cap.getRGB(0, 0))
        assertTrue((cap.getRGB(0, 5) ushr 24) in 1..101)
        assertEquals(cap.getRGB(0, 5), cap.getRGB(5, 0))
        assertEquals(cap.getRGB(0, 5), cap.getRGB(179, 5))
    }
}
