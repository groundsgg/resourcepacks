package gg.grounds.resourcepacks.product

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class DialogueContributionTest {
    @Test
    fun `dialogue bundle declares verified assets and Grounds palette`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)).use { it.readBytes() }
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(read("manifest.json"))
        val theme = mapper.readTree(read("theme.json"))
        assertEquals("grounds-26.3", manifest.get("profile").asString())
        assertEquals(manifest.get("pin").asString(), theme.get("assetPin").asString())
        assertEquals("F3F0EA", theme.get("text").asString())
        assertEquals("C1B9AA", theme.get("secondary").asString())
        assertEquals("E7B84B", theme.get("accent").asString())
        assertTrue(manifest.get("files").has("assets/dialogue/font-source/font-license.txt"))
        DialogueContribution.create()
    }

    @Test
    fun `every imported native dialogue glyph advance matches its atlas ink`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)).use { it.readBytes() }
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(read("manifest.json"))
        val glyphs = manifest.get("glyphs")
        assertEquals(200, glyphs.size())

        val atlas =
            assertNotNull(
                ImageIO.read(
                    ByteArrayInputStream(read("assets/dialogue/textures/font/letters.png"))
                )
            )
        assertEquals(256, atlas.width)
        assertEquals(0, atlas.width % 16)
        assertEquals(0, atlas.height % 256)

        glyphs
            .properties()
            .sortedBy { it.key.toInt() }
            .forEach { (codepoint, glyph) ->
                val character = "U+${codepoint.toInt().toString(16).uppercase().padStart(4, '0')}"
                val column = glyph.get("column").asInt()
                val row = glyph.get("row").asInt()
                val advance = glyph.get("advance").asInt()
                assertTrue(column in 0 until atlas.width / 16, "U+$codepoint column=$column")
                assertTrue(row in 0 until atlas.height / 256, "U+$codepoint row=$row")

                var rightmostAlpha = -1
                for (y in row * 256 until (row + 1) * 256) {
                    for (x in column * 16 until (column + 1) * 16) {
                        if (atlas.getRGB(x, y) ushr 24 != 0) {
                            rightmostAlpha = maxOf(rightmostAlpha, x - column * 16)
                        }
                    }
                }

                if (rightmostAlpha < 0) {
                    assertEquals(advance, 1, "blank $character")
                } else {
                    assertEquals(advance, rightmostAlpha + 2, character)
                }
            }
    }
}
