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
        assertEquals(0.20, theme.get("vignetteStrength").asDouble())
        assertTrue(manifest.get("files").has("assets/dialogue/font-source/font-license.txt"))
        DialogueContribution.create()
    }

    @Test
    fun `imported bundle contains all four vignette fade strengths`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)) {
                    "Missing imported dialogue asset: $name"
                }
                .use { it.readBytes() }
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(read("manifest.json"))
        val strengths = listOf("0.03", "0.06", "0.09", "0.12")
        strengths.forEachIndexed { index, strength ->
            val suffix = index + 1
            val effectPath = "assets/dialogue/post_effect/vignette_fade_${suffix}.json"
            val shaderPath = "assets/dialogue/shaders/post/vignette_fade_${suffix}.fsh"
            assertTrue(manifest.get("files").has(effectPath), effectPath)
            assertTrue(manifest.get("files").has(shaderPath), shaderPath)
            val effect = mapper.readTree(read(effectPath))
            assertEquals(
                "dialogue:post/vignette_fade_${suffix}",
                effect.get("passes").get(0).get("fragment_shader").asString(),
            )
            val shader = String(read(shaderPath), Charsets.UTF_8)
            assertTrue(shader.contains("scene.rgb * (1.0 - ${strength} * edge)"), shaderPath)
        }
    }

    @Test
    fun `imported bundle contains native vignette clocks and paired effects`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)) { name }.use { it.readBytes() }
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(read("manifest.json"))
        for (bank in listOf("a", "b")) {
            for (mode in listOf("enter", "exit")) {
                val path = "assets/dialogue/post_effect/vignette_250_${mode}_${bank}.json"
                assertTrue(manifest.get("files").has(path), path)
                val effect = mapper.readTree(read(path))
                val clock = effect.get("targets").get("clock")
                assertEquals(1, clock.get("width").asInt())
                assertEquals(1, clock.get("height").asInt())
                assertTrue(clock.get("persistent").asBoolean())
                assertEquals(0, clock.get("clear_color").asInt())
                assertEquals(4, effect.get("passes").size())
                for (passIndex in 0..1) {
                    val uniforms =
                        effect.get("passes").get(passIndex).get("uniforms").get("FadeConfig")
                    val values =
                        (0 until uniforms.size()).associate { index ->
                            val value = uniforms.get(index)
                            value.get("name").asString() to value.get("value").asDouble()
                        }
                    assertEquals(5.0, values["DurationTicks"])
                    assertEquals(0.0, values["From"])
                    assertEquals(0.20, values["To"])
                    assertEquals(if (mode == "exit") 1.0 else 0.0, values["Inverse"])
                    assertEquals(setOf("DurationTicks", "From", "To", "Inverse"), values.keys)
                }
            }
        }
        for (name in listOf("vignette_state", "vignette_animated", "vignette_copy")) {
            assertTrue(manifest.get("files").has("assets/dialogue/shaders/post/$name.fsh"))
        }
        assertTrue(manifest.get("files").has("assets/dialogue/shaders/include/vignette_clock.glsl"))
        assertTrue(
            String(read("assets/dialogue/shaders/post/vignette_state.fsh"))
                .contains("#include <minecraft:globals.glsl>")
        )
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
