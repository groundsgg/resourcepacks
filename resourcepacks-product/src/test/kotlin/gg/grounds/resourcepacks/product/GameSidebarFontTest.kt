package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*
import tools.jackson.databind.json.JsonMapper

class GameSidebarFontTest {
    @Test
    fun `all eight game symbols have an eight pixel footprint and nine pixel advance`() {
        val names = listOf("bed", "team", "progress", "coins", "map", "players", "swords", "trophy")
        val font = GameSidebarFont.json()
        val providers = JsonMapper.builder().build().readTree(font).get("providers")
        names.forEachIndexed { i, name ->
            val chars = providers.get(i).get("chars").get(0).asString()
            assertEquals((0xE340 + i).toChar().toString(), chars)
            assertTrue("\\u%04X".format(0xE340 + i) in font)
            assertTrue("game_sidebar_$name.png" in font)
            val image =
                ImageIO.read(
                    ByteArrayInputStream(
                        GameSidebarFont.textures().getValue("game_sidebar_$name.png")
                    )
                )
            assertEquals(24, image.width)
            assertEquals(24, image.height)
            assertTrue(
                (0 until image.height).any { y ->
                    (0 until image.width).any { x -> image.getRGB(x, y) ushr 24 != 0 }
                }
            )
            // Width8 + vanilla's one-pixel provider advance matches the shared renderer.
            assertTrue((0 until image.height).any { y -> image.getRGB(23, y) ushr 24 != 0 })
        }
        assertTrue("\"height\":8,\"ascent\":7" in font)
    }
}
