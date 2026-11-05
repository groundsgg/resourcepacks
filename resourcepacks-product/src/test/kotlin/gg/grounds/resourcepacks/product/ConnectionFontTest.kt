package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class ConnectionFontTest {
    @Test
    fun `connection glyphs select distinct visible hard-edged icons on the chat baseline`() {
        val font = JsonMapper.builder().build().readTree(ConnectionFont.json())
        val provider = font.get("providers").get(0)
        assertEquals("\uE410\uE411\uE412", provider.get("chars").get(0).asString())
        assertEquals(10, provider.get("height").asInt())
        assertEquals(8, provider.get("ascent").asInt())
        val image = ImageIO.read(ByteArrayInputStream(ConnectionFont.texture()))
        assertEquals(30, image.width)
        assertEquals(10, image.height)
        val cells =
            (0..2).map { index ->
                (0 until 10).flatMap { y ->
                    (0 until 10).map { x -> image.getRGB(index * 10 + x, y) }
                }
            }
        assertEquals(3, cells.toSet().size)
        cells.forEach { pixels ->
            assertTrue(pixels.count { it ushr 24 != 0 } >= 20)
            assertTrue(pixels.all { it ushr 24 == 0 || it ushr 24 == 255 })
            assertTrue(pixels.contains(0xFF67D9EC.toInt()))
        }
    }
}
