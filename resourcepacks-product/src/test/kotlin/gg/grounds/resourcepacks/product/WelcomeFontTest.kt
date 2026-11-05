package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class WelcomeFontTest {
    @Test
    fun `decorated wordmark has its own bounded bitmap provider`() {
        val font = JsonMapper.builder().build().readTree(WelcomeFont.json())
        val logo = font.get("providers").get(1)
        assertEquals("grounds:font/welcome_logo.png", logo.get("file").asString())
        assertEquals("\uE403\uE404", logo.get("chars").get(0).asString())
        assertEquals(42, logo.get("height").asInt())
        assertEquals(41, logo.get("ascent").asInt())
        val join = font.get("providers").get(2)
        assertEquals("space", join.get("type").asString())
        val joinAdvance = join.get("advances").get("\uE405").asDouble()
        assertEquals(42.0 * 256 / 241, 46.0 + joinAdvance, 0.00001)
        val root =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val image = ImageIO.read(root.resolve("art/platform/welcome/logo.png").toFile())
        assertTrue(
            image.width / 2 <= 256 && image.height <= 256,
            "Each bitmap cell must fit Minecraft's 256x256 native font atlas",
        )
        assertEquals(512, image.width)
        assertEquals(241, image.height)
        assertTrue(
            (0 until image.height / 3).any { y ->
                (0 until image.width).any { x -> image.getRGB(x, y) ushr 24 != 0 }
            },
            "Floating stones above the wordmark must remain present",
        )
    }

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
        assertEquals(
            12,
            provider.get("ascent").asInt(),
            "Keep full icon bitmap above the adjacent voice row",
        )
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
