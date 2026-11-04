package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class WelcomeFontTest {
    @Test
    fun `each branded glyph selects a distinct pixel icon from the atlas`() {
        val font = JsonMapper.builder().build().readTree(WelcomeFont.json())
        val provider = font.get("providers").get(0)
        val chars = provider.get("chars").get(0).asString()
        val image = ImageIO.read(ByteArrayInputStream(WelcomeFont.texture()))
        assertEquals("\uE400\uE401\uE402", chars)
        assertEquals(42, image.width)
        assertEquals(14, image.height)
        assertEquals(14, provider.get("height").asInt())
        val cells =
            (0..2).map { i ->
                (0 until 14).flatMap { y -> (0 until 14).map { x -> image.getRGB(i * 14 + x, y) } }
            }
        assertEquals(3, cells.toSet().size)
        cells.forEach { pixels ->
            assertTrue(pixels.any { it ushr 24 == 0 })
            assertTrue(pixels.count { it ushr 24 != 0 } >= 40)
            assertTrue(
                pixels.all { it ushr 24 == 0 || it ushr 24 == 255 },
                "Pixels must have hard edges",
            )
        }
        assertTrue(cells[0].contains(0xFF5865F2.toInt()))
        assertTrue(
            cells[1].containsAll(listOf(0xFF25F4EE.toInt(), 0xFFFE2C55.toInt(), 0xFFFFFFFF.toInt()))
        )
        assertTrue(cells[2].contains(0xFFE7B84B.toInt()))
    }
}
